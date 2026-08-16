/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Auto-freeze engine: schedules a freeze after an app leaves foreground and
 * thaws it immediately on return, rechecking exemptions/whitelist before freezing.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

import android.app.usage.UsageEvents
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

// Driven by ActivityTaskManagerService#updateActivityUsageStats events on a dedicated thread.
// Engine exceptions are swallowed so system_server is never harmed.
class FreezeEngine(
    private val xposed: XposedInterface,
    private val freezeController: FreezeController,
    private val exemptionDetector: AppExemptionDetector,
    private val vpnUids: Set<Int>,
) {
    private val policyStore = FreezePolicyStore(xposed)
    private val states = ConcurrentHashMap<String, AppFreezeState>()
    private val engineFrozenKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val freezeDeadlines = ConcurrentHashMap<String, Long>()

    // uid -> "userId#pkg" for the apps the engine has frozen. Lets the binder hot path decide
    // "is this uid frozen?" with an O(1) lookup, no cgroup read or PackageManager. Maintained in
    // lockstep with engineFrozenKeys.
    private val frozenUidToKey = ConcurrentHashMap<Int, String>()

    @Volatile
    private var context: Context? = null

    @Volatile
    private var dryRun = false

    @Volatile
    private var bridge: SystemServerRuntimeBridge? = null

    @Volatile
    private var active = true

    private val thread: HandlerThread
    private val handler: Handler

    init {
        thread = HandlerThread("Nemuri-Freezer")
        thread.setUncaughtExceptionHandler { _, e ->
            xposed.log(Log.ERROR, TAG, "Freezer thread uncaught", e)
        }
        thread.start()
        handler = object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                if (!active) return
                try {
                    if (msg.what == MSG_FREEZE && msg.obj is String) {
                        val key = msg.obj as String
                        freezeDeadlines.remove(key)
                        freezer(key)
                    } else if (msg.what == MSG_SWEEP) {
                        sweepBackgroundApps()
                        // reschedule the next sweep
                        sendMessageDelayed(obtainMessage(MSG_SWEEP), SWEEP_INTERVAL_MS)
                    }
                } catch (throwable: Throwable) {
                    xposed.log(Log.WARN, TAG, "handleMessage failed", throwable)
                }
            }
        }
    }

    fun setContext(context: Context) {
        this.context = context
    }

    fun setBridge(bridge: SystemServerRuntimeBridge) {
        this.bridge = bridge
    }

    fun setDryRun(dryRun: Boolean) {
        this.dryRun = dryRun
    }

    fun snapshotAndStopForHotReload(): Array<Any?> {
        active = false
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        try {
            thread.join(STOP_TIMEOUT_MS)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val visibleTokens = HashMap<String, ArrayList<IBinder>>()
        for ((key, state) in states) {
            val tokens = state.snapshotTokens()
            if (tokens.isNotEmpty()) visibleTokens[key] = tokens
        }
        val snapshot = arrayOf<Any?>(
            visibleTokens,
            HashMap(frozenUidToKey),
            HashMap(freezeDeadlines),
            dryRun,
        )
        freezeDeadlines.clear()
        return snapshot
    }

    fun restoreAfterHotReload(snapshot: Array<*>) {
        policyStore.load()
        states.clear()
        val visibleTokens = snapshot.getOrNull(0) as? Map<*, *>
        if (visibleTokens != null) {
            for ((keyValue, tokenValue) in visibleTokens) {
                val key = keyValue as? String ?: continue
                val tokens = (tokenValue as? Collection<*>)?.filterIsInstance<IBinder>() ?: continue
                if (tokens.isNotEmpty()) {
                    states.getOrPut(key) { AppFreezeState() }.restoreTokens(tokens)
                }
            }
        }

        engineFrozenKeys.clear()
        frozenUidToKey.clear()
        val frozen = snapshot.getOrNull(1) as? Map<*, *>
        if (frozen != null) {
            for ((uidValue, keyValue) in frozen) {
                val uid = uidValue as? Int ?: continue
                val key = keyValue as? String ?: continue
                if (freezeController.isFrozen(uid)) {
                    frozenUidToKey[uid] = key
                    engineFrozenKeys.add(key)
                }
            }
        }

        dryRun = snapshot.getOrNull(3) as? Boolean ?: false
        val now = android.os.SystemClock.uptimeMillis()
        val deadlines = snapshot.getOrNull(2) as? Map<*, *>
        if (deadlines != null && policyStore.isEnabled()) {
            for ((keyValue, deadlineValue) in deadlines) {
                val key = keyValue as? String ?: continue
                val deadline = deadlineValue as? Long ?: continue
                scheduleFreeze(key, (deadline - now).coerceAtLeast(0L))
            }
        }
        handler.removeMessages(MSG_SWEEP)
        handler.sendMessageDelayed(handler.obtainMessage(MSG_SWEEP), SWEEP_FIRST_DELAY_MS)
    }

    // Called at boot completion: load persisted policy and clear any stale frozen apps left in
    // cgroup from a previous run (the engine re-drives freezing from fresh activity events, so
    // nothing should start out frozen -- this recovers anything stuck across a framework reload).
    fun onBoot() {
        policyStore.load()
        thawAllFrozenApps()
        handler.removeMessages(MSG_SWEEP)
        handler.sendMessageDelayed(handler.obtainMessage(MSG_SWEEP), SWEEP_FIRST_DELAY_MS)
    }

    private fun thawAllFrozenApps() {
        try {
            freezeController.thawAllFrozen()
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "thawAllFrozenApps failed", throwable)
        }
    }

    fun applyPolicy(enabled: Boolean, delayMs: Long, binderUnfreeze: Boolean, whitelist: Set<String>): Boolean {
        if (!active) return false
        val wasEnabled = policyStore.isEnabled()
        policyStore.apply(enabled, delayMs, binderUnfreeze, whitelist)
        if (wasEnabled && !enabled) {
            thawAllEngineFrozen() // turning the engine off releases everything it froze
        }
        return true
    }

    // From the ProcessList#startProcessLocked hook: a process is being (re)started. Schedule a
    // freeze so apps launched by any path (boot autostart, pulled up by others, woken) get caught
    // immediately, not only by the periodic sweep. freezer() does the full recheck on fire.
    // Kept extremely light: this runs in the AMS lock on a hot path; real work is on our thread.
    fun onProcessStarted(packageName: String?, uid: Int) {
        if (!active
            || !policyStore.isEnabled()
            || packageName.isNullOrEmpty()
            || NemuriBridgeProtocol.MANAGER_PACKAGE_NAME == packageName
            || !freezeController.isAppUid(uid)
            || policyStore.isWhitelisted(packageName)
        ) {
            return
        }
        val key = (uid / 100000).toString() + "#" + packageName
        val state = states[key]
        if (state != null && state.isVisible()) return
        if (!freezeDeadlines.containsKey(key)) {
            scheduleFreeze(key, policyStore.getDelayMs())
        }
    }

    // From the ATMS hook. activity may be null for some events; event is a UsageEvents.Event.
    fun onActivityEvent(activity: ComponentName?, userId: Int, event: Int, token: IBinder?) {
        if (!active || activity == null || token == null) return
        val pkg = activity.packageName
        if (pkg.isNullOrEmpty()) return
        val key = "$userId#$pkg"
        val state = states.computeIfAbsent(key) { AppFreezeState() }

        val transitioned: Boolean = when (event) {
            UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED ->
                state.addActivity(token)
            UsageEvents.Event.ACTIVITY_STOPPED, ACTIVITY_DESTROYED ->
                state.removeActivity(token)
            else -> return // other events don't affect visibility
        }

        if (!transitioned) return
        if (state.isVisible()) {
            // back to foreground: cancel pending freeze, thaw now
            cancelFreeze(key)
            thawNow(key, pkg, userId)
            if (RuntimeLog.verbose) {
                xposed.log(Log.DEBUG, TAG, "foreground $pkg")
            }
        } else {
            // went to background: schedule delayed freeze
            if (!freezeDeadlines.containsKey(key)) {
                scheduleFreeze(key, policyStore.getDelayMs())
                if (RuntimeLog.verbose) {
                    xposed.log(Log.DEBUG, TAG, "background $pkg -> freeze in ${policyStore.getDelayMs()}ms")
                }
            }
        }
    }

    // Periodic catch-all: schedule a freeze for any background app that has no pending timer and
    // isn't frozen yet. freezer() does the full recheck when the timer fires, so this only needs
    // to ensure a timer exists. Covers boot-autostarted apps that never sent an activity event.
    private fun sweepBackgroundApps() {
        if (!active || !policyStore.isEnabled()) return
        val b = bridge ?: return
        for (ref in b.snapshotBackgroundApps()) {
            if (NemuriBridgeProtocol.MANAGER_PACKAGE_NAME == ref.packageName
                || policyStore.isWhitelisted(ref.packageName)
                || !freezeController.isAppUid(ref.uid)
                || freezeController.isFrozen(ref.uid)
            ) {
                continue
            }
            val key = (ref.uid / 100000).toString() + "#" + ref.packageName
            val state = states[key]
            if (state != null && state.isVisible()) continue // currently foreground
            if (!freezeDeadlines.containsKey(key)) {
                scheduleFreeze(key, policyStore.getDelayMs())
            }
        }
    }

    private fun freezer(key: String) {
        if (!active || !policyStore.isEnabled()) return
        val pkg = packageOf(key)
        val userId = userIdOf(key)
        if (NemuriBridgeProtocol.MANAGER_PACKAGE_NAME == pkg) return // never freeze ourselves
        if (policyStore.isWhitelisted(pkg)) return
        val state = states[key]
        if (state != null && state.isVisible()) return // came back to foreground while queued
        val ctx = context ?: return
        val uid = resolveUid(ctx, pkg, userId)
        if (!freezeController.isAppUid(uid)) return
        val flags = exemptionDetector.flagsFor(ctx, pkg, uid, exemptionDetector.snapshot(ctx, vpnUids))
        if (flags != NemuriBridgeProtocol.EXEMPT_NONE) {
            return // audio/mic/vpn/ime/launcher/a11y/system/xposed/self -> skip
        }
        if (dryRun) {
            xposed.log(Log.INFO, TAG, "would freeze $pkg uid=$uid")
            return
        }
        if (freezeController.setFrozen(uid, true)) {
            engineFrozenKeys.add(key)
            frozenUidToKey[uid] = key
        }
    }

    // Always thaw on return-to-foreground based on the ACTUAL cgroup state, not on whether the
    // engine remembers freezing it. engineFrozenKeys is in-memory and lost across a framework
    // reload, while cgroup.freeze persists -- relying on it would leave apps frozen-stuck after a
    // reload. Checking the real state also recovers anything left frozen for any reason.
    private fun thawNow(key: String, pkg: String, userId: Int) {
        engineFrozenKeys.remove(key)
        val ctx = context ?: return
        val uid = resolveUid(ctx, pkg, userId)
        frozenUidToKey.remove(uid)
        if (freezeController.isAppUid(uid) && freezeController.isFrozen(uid)) {
            freezeController.setFrozen(uid, false)
        }
    }

    private fun thawAllEngineFrozen() {
        val ctx = context
        for (key in engineFrozenKeys) {
            engineFrozenKeys.remove(key)
            if (ctx != null) {
                val uid = resolveUid(ctx, packageOf(key), userIdOf(key))
                frozenUidToKey.remove(uid)
                if (freezeController.isAppUid(uid)) {
                    freezeController.setFrozen(uid, false)
                }
            }
        }
        frozenUidToKey.clear()
    }

    // O(1) hot-path check for the binder hook: is this uid currently frozen by the engine?
    fun isUidFrozenFast(uid: Int): Boolean = frozenUidToKey.containsKey(uid)

    // Called from reportBinderTrans (binder hot path) when a frozen app is the target of a binder
    // transaction: thaw it for a window so the call can be served, then re-freeze. Hot path: the
    // O(1) map lookup returns immediately for the vast majority (non-frozen uids).
    //
    // @param force thaw even with binder unfreeze switched off. That setting governs speculative
    //   thawing on binder traffic; a caller passing force has evidence the app is already stalling
    //   something (an ANR), where refusing to thaw would hide the symptom and leave the stall.
    fun temporaryUnfreeze(uid: Int, reason: String, durationMs: Long, force: Boolean = false) {
        if (!active) return
        val key = frozenUidToKey[uid] ?: return
        if (!force && !policyStore.isBinderUnfreezeEnabled()) return
        try {
            if (freezeController.isAppUid(uid) && freezeController.isFrozen(uid)) {
                freezeController.setFrozen(uid, false)
            }
            engineFrozenKeys.remove(key)
            frozenUidToKey.remove(uid)
            if (RuntimeLog.verbose) {
                xposed.log(Log.DEBUG, TAG, "tmp-thaw $key reason=$reason ${durationMs}ms")
            }
            // Re-freeze after the window via freezer(), which re-checks exemptions/whitelist/visibility
            // (so a return-to-foreground or whitelisting during the window won't get re-frozen).
            scheduleFreeze(key, durationMs)
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "temporaryUnfreeze failed uid=$uid", throwable)
        }
    }

    private fun scheduleFreeze(key: String, delayMs: Long) {
        if (!active) return
        handler.removeMessages(MSG_FREEZE, key)
        val safeDelay = delayMs.coerceAtLeast(0L)
        freezeDeadlines[key] = android.os.SystemClock.uptimeMillis() + safeDelay
        handler.sendMessageDelayed(handler.obtainMessage(MSG_FREEZE, key), safeDelay)
    }

    private fun cancelFreeze(key: String) {
        freezeDeadlines.remove(key)
        handler.removeMessages(MSG_FREEZE, key)
    }

    private fun resolveUid(ctx: Context, pkg: String, userId: Int): Int = try {
        val base = ctx.packageManager.getPackageUid(pkg, 0)
        val appId = base % 100000
        userId * 100000 + appId
    } catch (ignored: Throwable) {
        -1
    }

    private fun packageOf(key: String): String {
        val idx = key.indexOf('#')
        return if (idx >= 0) key.substring(idx + 1) else key
    }

    private fun userIdOf(key: String): Int {
        val idx = key.indexOf('#')
        return if (idx >= 0) key.substring(0, idx).toIntOrNull() ?: 0 else 0
    }

    private companion object {
        const val TAG = "Nemuri"
        const val MSG_FREEZE = 0
        const val MSG_SWEEP = 1
        // UsageEvents.Event.ACTIVITY_DESTROYED is @hide; use its literal value (ACTIVITY_STOPPED + 1).
        const val ACTIVITY_DESTROYED = 24
        // Low-frequency safety-net sweep. The process-start hook handles autostart/woken apps in real
        // time; this only catches anything the hooks might have missed, so it can be infrequent.
        const val SWEEP_FIRST_DELAY_MS = 60_000L
        const val SWEEP_INTERVAL_MS = 300_000L
        const val STOP_TIMEOUT_MS = 1000L
    }
}
