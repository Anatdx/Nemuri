/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - cgroup v2 freeze primitive: freezes/thaws an app by uid via cgroup.freeze.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;

import io.github.libxposed.api.XposedInterface;

// Writes cgroup v2 cgroup.freeze for an app uid. Runs in system_server, which owns these files
// (the framework's own freezer writes them too), so no root is needed. uid-level freeze cascades
// to every process of the app, including ones spawned later. Application uids (10000-19999) only.
final class FreezeController {
    private static final String TAG = "Nemuri";
    private static final String CGROUP_V2 = "/sys/fs/cgroup";
    private static final int PER_USER_RANGE = 100000;
    private static final int FIRST_APPLICATION_UID = 10000;
    private static final int LAST_APPLICATION_UID = 19999;

    private final XposedInterface xposed;
    // Isolated layout (apps/ + system/) vs flat (uid_X/), detected once like Cirno's FrozenRW.
    private final boolean isolatedLayout;

    FreezeController(@NonNull XposedInterface xposed) {
        this.xposed = xposed;
        this.isolatedLayout = !new File(CGROUP_V2 + "/uid_1000/cgroup.freeze").exists();
    }

    boolean isAppUid(int uid) {
        int appId = uid % PER_USER_RANGE;
        return appId >= FIRST_APPLICATION_UID && appId <= LAST_APPLICATION_UID;
    }

    boolean setFrozen(int uid, boolean frozen) {
        if (!isAppUid(uid)) {
            xposed.log(Log.WARN, TAG, "Refusing to freeze non-application uid " + uid);
            return false;
        }
        try (PrintWriter writer = new PrintWriter(freezePath(uid))) {
            writer.write(frozen ? "1" : "0");
            xposed.log(Log.INFO, TAG, "cgroup.freeze=" + (frozen ? 1 : 0) + " uid=" + uid);
            return true;
        } catch (Throwable throwable) {
            xposed.log(Log.WARN, TAG, "Failed to write cgroup.freeze for uid " + uid, throwable);
            return false;
        }
    }

    boolean isFrozen(int uid) {
        if (!isAppUid(uid)) {
            return false;
        }
        File file = new File(freezePath(uid));
        if (!file.exists()) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line != null && line.trim().startsWith("1");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String freezePath(int uid) {
        String base = isolatedLayout ? CGROUP_V2 + "/apps/uid_" : CGROUP_V2 + "/uid_";
        return base + uid + "/cgroup.freeze";
    }
}
