/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Per-app foreground/background state for the auto-freeze engine.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

import android.os.IBinder

// Tracks an app's visible activity tokens. visible flips only when the set goes empty<->non-empty;
// setVisible returns true only on an actual transition, so the engine schedules/cancels once.
class AppFreezeState {
    private val visibleActivities = HashSet<IBinder>()
    private var visible = false

    @Synchronized
    fun addActivity(token: IBinder): Boolean {
        visibleActivities.add(token)
        return setVisible(visibleActivities.isNotEmpty())
    }

    @Synchronized
    fun removeActivity(token: IBinder): Boolean {
        visibleActivities.remove(token)
        return setVisible(visibleActivities.isNotEmpty())
    }

    @Synchronized
    fun isVisible(): Boolean = visible

    private fun setVisible(value: Boolean): Boolean {
        if (visible == value) return false
        visible = value
        return true
    }
}
