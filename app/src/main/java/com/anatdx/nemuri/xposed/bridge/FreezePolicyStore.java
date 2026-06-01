/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Auto-freeze policy: master switch, delay, and never-freeze whitelist,
 * persisted at /data/misc/Nemuri/policy.json (readable by system_server across reboots).
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

final class FreezePolicyStore {
    private static final String TAG = "Nemuri";
    private static final String DIR = "/data/misc/Nemuri";
    private static final String FILE = DIR + "/policy.json";
    private static final long DEFAULT_DELAY_MS = 5000L;

    private final XposedInterface xposed;
    private volatile boolean autoFreezeEnabled = false; // default off for safety
    private volatile long freezeDelayMs = DEFAULT_DELAY_MS;
    private final Set<String> whitelist = ConcurrentHashMap.newKeySet();

    FreezePolicyStore(@NonNull XposedInterface xposed) {
        this.xposed = xposed;
    }

    boolean isEnabled() {
        return autoFreezeEnabled;
    }

    long getDelayMs() {
        return freezeDelayMs;
    }

    boolean isWhitelisted(@NonNull String pkg) {
        return whitelist.contains(pkg);
    }

    Set<String> whitelistSnapshot() {
        return Collections.unmodifiableSet(new HashSet<>(whitelist));
    }

    void apply(boolean enabled, long delayMs, @NonNull Set<String> newWhitelist) {
        autoFreezeEnabled = enabled;
        freezeDelayMs = delayMs > 0 ? delayMs : DEFAULT_DELAY_MS;
        whitelist.clear();
        whitelist.addAll(newWhitelist);
        save();
    }

    void load() {
        try {
            File file = new File(FILE);
            if (!file.exists()) {
                return; // keep defaults (disabled)
            }
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            JSONObject root = new JSONObject(sb.toString());
            autoFreezeEnabled = root.optBoolean("autoFreezeEnabled", false);
            freezeDelayMs = root.optLong("freezeDelayMs", DEFAULT_DELAY_MS);
            whitelist.clear();
            JSONArray arr = root.optJSONArray("whitelist");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String pkg = arr.optString(i, null);
                    if (pkg != null && !pkg.isEmpty()) {
                        whitelist.add(pkg);
                    }
                }
            }
            xposed.log(Log.INFO, TAG, "Policy loaded: enabled=" + autoFreezeEnabled
                    + " delay=" + freezeDelayMs + " whitelist=" + whitelist.size());
        } catch (Throwable throwable) {
            xposed.log(Log.WARN, TAG, "Failed to load policy.json; using defaults", throwable);
        }
    }

    private void save() {
        try {
            File dir = new File(DIR);
            if (!dir.exists()) {
                // system_server creates this under /data/misc, inheriting system_data_file context.
                dir.mkdirs();
                dir.setReadable(false, false);
                dir.setReadable(true, true);
                dir.setWritable(false, false);
                dir.setWritable(true, true);
                dir.setExecutable(false, false);
                dir.setExecutable(true, true);
            }
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("autoFreezeEnabled", autoFreezeEnabled);
            root.put("freezeDelayMs", freezeDelayMs);
            JSONArray arr = new JSONArray();
            for (String pkg : whitelist) {
                arr.put(pkg);
            }
            root.put("whitelist", arr);
            try (java.io.FileWriter w = new java.io.FileWriter(FILE)) {
                w.write(root.toString(2));
            }
            xposed.log(Log.INFO, TAG, "Policy saved: enabled=" + autoFreezeEnabled
                    + " delay=" + freezeDelayMs + " whitelist=" + whitelist.size());
        } catch (Throwable throwable) {
            xposed.log(Log.WARN, TAG, "Failed to save policy.json", throwable);
        }
    }
}
