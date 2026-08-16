/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Drops the ANRs that freezing causes, and thaws the app so the stuck work can drain.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * A frozen app cannot answer anything, so whatever was waiting on it -- a synchronous binder call, a
 * broadcast, a service start -- eventually times out and the framework files an ANR against it. The
 * app did nothing wrong: we stopped it. Left alone this surfaces as "app isn't responding" dialogs
 * and system-initiated kills, i.e. Nemuri visibly breaking apps.
 *
 * So: when an ANR is filed against a uid we froze, drop it and thaw the app. Thawing matters as much
 * as dropping -- an ANR means something is genuinely blocked waiting on this app, and letting it run
 * lets that work finish. The engine re-freezes afterwards through its normal path.
 *
 * Two things this deliberately does not do:
 *  - suppress ANRs for apps we did not freeze (those are real bugs and must stay visible)
 *  - suppress forever after a thaw. An ANR filed moments after thawing usually accumulated its
 *    timeout while the app was still frozen, so a short grace window after thawing is also covered;
 *    beyond that window ANRs are the app's own.
 */
class AnrSuppressor(
    private val xposed: XposedInterface,
    private val freezeEngine: FreezeEngine,
) {
    // uid -> uptimeMillis when we thawed it. An ANR arriving shortly after a thaw was almost
    // certainly incurred while frozen, so it is still ours to swallow.
    private val recentlyThawed = ConcurrentHashMap<Int, Long>()

    @Volatile
    private var processRecordUidField: Field? = null

    // ANRs can be filed from several system_server threads at once.
    private val suppressed = java.util.concurrent.atomic.AtomicLong()

    fun onThawed(uid: Int) {
        recentlyThawed[uid] = android.os.SystemClock.uptimeMillis()
        if (recentlyThawed.size > MAX_TRACKED) pruneRecentlyThawed()
    }

    /**
     * @param target the ProcessRecord (or AnrRecord) the ANR is being filed against
     * @return true when the caller should skip the original ANR handling entirely
     */
    fun shouldSuppress(target: Any?): Boolean {
        val uid = resolveUid(target) ?: return false
        if (!isOurs(uid)) return false

        xposed.log(
            Log.INFO, TAG,
            "Suppressed ANR for frozen uid=$uid (total=${suppressed.incrementAndGet()});" +
                " thawing so pending work drains"
        )
        // An ANR means someone is blocked on this app. Give it a window to run; the engine's
        // re-freeze path rechecks visibility and exemptions when the window closes. Forced, because
        // swallowing the ANR without thawing would hide a stall that is already happening.
        freezeEngine.temporaryUnfreeze(uid, "ANR", ANR_THAW_MS, force = true)
        return true
    }

    private fun isOurs(uid: Int): Boolean {
        if (freezeEngine.isUidFrozenFast(uid)) return true
        val thawedAt = recentlyThawed[uid] ?: return false
        val age = android.os.SystemClock.uptimeMillis() - thawedAt
        if (age in 0..THAW_GRACE_MS) return true
        recentlyThawed.remove(uid)
        return false
    }

    /**
     * Accepts a ProcessRecord directly, or any of the wrappers the ANR paths hand us
     * (AnrHelper$AnrRecord, ProcessErrorStateRecord) -- both hold the ProcessRecord in mApp.
     */
    private fun resolveUid(target: Any?): Int? {
        if (target == null) return null
        val record = if (hasUidField(target)) target else unwrapApp(target) ?: return null
        val field = uidField(record) ?: return null
        return try {
            field.getInt(record).takeIf { it > 0 }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun hasUidField(target: Any): Boolean = uidField(target) != null

    private fun unwrapApp(target: Any): Any? = try {
        findField(target.javaClass, "mApp")?.get(target)
    } catch (ignored: Throwable) {
        null
    }

    private fun uidField(record: Any): Field? {
        // Cached only while it still applies to the class in hand; the ANR paths hand us more than
        // one type, so a blindly cached field would be read against the wrong object.
        processRecordUidField?.let {
            if (it.declaringClass.isAssignableFrom(record.javaClass)) return it
        }
        val field = findField(record.javaClass, "uid") ?: return null
        if (field.type != Int::class.javaPrimitiveType) return null
        processRecordUidField = field
        return field
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (ignored: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun pruneRecentlyThawed() {
        val now = android.os.SystemClock.uptimeMillis()
        recentlyThawed.entries.removeAll { now - it.value > THAW_GRACE_MS }
    }

    private companion object {
        const val TAG = "Nemuri"

        // How long to let an ANR'd app run before the engine may re-freeze it. Longer than the
        // binder window: an ANR means work was already blocked for seconds, so it needs room.
        const val ANR_THAW_MS = 10_000L

        // An ANR filed within this long after a thaw is treated as incurred while frozen.
        const val THAW_GRACE_MS = 10_000L
        const val MAX_TRACKED = 256
    }
}
