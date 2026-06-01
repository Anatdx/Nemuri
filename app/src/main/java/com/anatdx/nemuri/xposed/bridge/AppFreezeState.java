/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Per-app foreground/background state for the auto-freeze engine.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge;

import android.os.IBinder;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;

// Tracks an app's visible activity tokens. visible flips only when the set goes empty<->non-empty;
// setVisible returns true only on an actual transition, so the engine schedules/cancels once.
final class AppFreezeState {
    private final Set<IBinder> visibleActivities = new HashSet<>();
    private boolean visible = false;

    synchronized boolean addActivity(@NonNull IBinder token) {
        visibleActivities.add(token);
        return setVisible(!visibleActivities.isEmpty());
    }

    synchronized boolean removeActivity(@NonNull IBinder token) {
        visibleActivities.remove(token);
        return setVisible(!visibleActivities.isEmpty());
    }

    synchronized boolean isVisible() {
        return visible;
    }

    private boolean setVisible(boolean value) {
        if (visible == value) {
            return false;
        }
        visible = value;
        return true;
    }
}
