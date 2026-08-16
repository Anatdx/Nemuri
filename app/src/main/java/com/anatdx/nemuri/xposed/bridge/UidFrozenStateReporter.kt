/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Tells the framework when a uid's freeze state changes, so its own bookkeeping matches
 * the cgroup we just wrote.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * Nemuri writes cgroup.freeze directly, which the framework never learns about on its own: it keeps
 * believing the app is runnable and goes on delivering broadcasts, alarms and binder calls into a
 * process that cannot answer. That mismatch is the root of several downstream problems, so mirror
 * every freeze/thaw into the same notification path the framework's own freezer uses.
 *
 * Android 14 added ActivityManagerService#reportUidFrozenStateChanged(int[], int[]) -- public, takes
 * batches, and drives mUidFrozenStateChangedCallbackList (which netd and app-facing
 * UidFrozenStateChangedCallback listeners sit behind). Preferred over CachedAppOptimizer's private
 * reportOneUidFrozenStateChanged/postUidFrozenMessage, which need the AMS proc lock held.
 *
 * Absent on older platforms; resolution failure just disables reporting.
 */
class UidFrozenStateReporter(private val xposed: XposedInterface) {
    @Volatile
    private var reportMethod: Method? = null

    @Volatile
    private var activityManagerService: Any? = null

    @Volatile
    private var resolved = false

    // What the framework has already been told, so a re-freeze of an already-frozen uid (sweep and
    // engine can both fire for one app) does not dispatch the whole callback list again.
    private val reportedFrozen = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    fun attach(ams: Any) {
        activityManagerService = ams
        if (resolved) return
        resolved = true
        reportMethod = try {
            ams.javaClass
                .getMethod("reportUidFrozenStateChanged", IntArray::class.java, IntArray::class.java)
                .apply { isAccessible = true }
                .also {
                    xposed.log(Log.INFO, TAG, "uid frozen-state reporting available")
                }
        } catch (ignored: NoSuchMethodException) {
            xposed.log(
                Log.INFO, TAG,
                "AMS#reportUidFrozenStateChanged absent; skipping uid frozen-state reporting"
            )
            null
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "Failed to resolve AMS#reportUidFrozenStateChanged", throwable)
            null
        }
    }

    fun report(uid: Int, frozen: Boolean) {
        val method = reportMethod ?: return
        val ams = activityManagerService ?: return
        // Redundant writes are common (sweep and engine can both freeze one app), and each report
        // fans out to every registered callback, so only announce real transitions.
        if (reportedFrozen.put(uid, frozen) == frozen) return
        val state = if (frozen) UID_FROZEN_STATE_FROZEN else UID_FROZEN_STATE_UNFROZEN
        try {
            method.invoke(ams, intArrayOf(uid), intArrayOf(state))
            if (RuntimeLog.verbose) {
                xposed.log(Log.DEBUG, TAG, "reported uid=$uid frozen=$frozen")
            }
        } catch (throwable: Throwable) {
            // Forget the attempt so a later transition is not deduplicated against a report the
            // framework never actually received.
            reportedFrozen.remove(uid)
            // Never let reporting break the freeze itself; the cgroup write already succeeded.
            xposed.log(Log.WARN, TAG, "Failed to report frozen state for uid $uid", throwable)
        }
    }

    private companion object {
        const val TAG = "Nemuri"

        // ActivityManager.UID_FROZEN_STATE_* (@SystemApi, values fixed by the platform).
        const val UID_FROZEN_STATE_UNFROZEN = 1
        const val UID_FROZEN_STATE_FROZEN = 2
    }
}
