/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - cgroup v2 freeze primitive: freezes/thaws an app by uid via cgroup.freeze.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.io.PrintWriter

// Writes cgroup v2 cgroup.freeze for an app uid. Runs in system_server, which owns these files
// (the framework's own freezer writes them too), so no root is needed. uid-level freeze cascades
// to every process of the app, including ones spawned later. Application uids (10000-19999) only.
class FreezeController(private val xposed: XposedInterface) {
    // Isolated layout (apps/ + system/) vs flat (uid_X/), detected once like Cirno's FrozenRW.
    private val isolatedLayout: Boolean =
        !File("$CGROUP_V2/uid_1000/cgroup.freeze").exists()

    fun isAppUid(uid: Int): Boolean {
        val appId = uid % PER_USER_RANGE
        return appId in FIRST_APPLICATION_UID..LAST_APPLICATION_UID
    }

    fun setFrozen(uid: Int, frozen: Boolean): Boolean {
        if (!isAppUid(uid)) {
            xposed.log(Log.WARN, TAG, "Refusing to freeze non-application uid $uid")
            return false
        }
        return try {
            PrintWriter(freezePath(uid)).use { it.write(if (frozen) "1" else "0") }
            if (RuntimeLog.verbose) {
                xposed.log(Log.INFO, TAG, "cgroup.freeze=" + (if (frozen) 1 else 0) + " uid=" + uid)
            }
            true
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "Failed to write cgroup.freeze for uid $uid", throwable)
            false
        }
    }

    fun isFrozen(uid: Int): Boolean {
        if (!isAppUid(uid)) return false
        val file = File(freezePath(uid))
        if (!file.exists()) return false
        return try {
            val line = file.bufferedReader().use { it.readLine() }
            line != null && line.trim().startsWith("1")
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun freezePath(uid: Int): String {
        val base = if (isolatedLayout) "$CGROUP_V2/apps/uid_" else "$CGROUP_V2/uid_"
        return "$base$uid/cgroup.freeze"
    }

    // Sweep every app cgroup and thaw any that are frozen. Used at boot to clear stale freezes
    // left over from a previous run so no app stays stuck.
    fun thawAllFrozen() {
        val dir = File(if (isolatedLayout) "$CGROUP_V2/apps" else CGROUP_V2)
        val children = dir.listFiles() ?: return
        var count = 0
        for (child in children) {
            val name = child.name
            if (!child.isDirectory || !name.startsWith("uid_")) continue
            val uid = name.removePrefix("uid_").toIntOrNull() ?: continue
            if (isAppUid(uid) && isFrozen(uid)) {
                if (writeFreeze(uid, false)) count++
            }
        }
        if (count > 0) {
            xposed.log(Log.INFO, TAG, "Thawed $count stale frozen app(s) at boot")
        }
    }

    private fun writeFreeze(uid: Int, frozen: Boolean): Boolean = try {
        PrintWriter(freezePath(uid)).use { it.write(if (frozen) "1" else "0") }
        true
    } catch (ignored: Throwable) {
        false
    }

    private companion object {
        const val TAG = "Nemuri"
        const val CGROUP_V2 = "/sys/fs/cgroup"
        const val PER_USER_RANGE = 100000
        const val FIRST_APPLICATION_UID = 10000
        const val LAST_APPLICATION_UID = 19999
    }
}
