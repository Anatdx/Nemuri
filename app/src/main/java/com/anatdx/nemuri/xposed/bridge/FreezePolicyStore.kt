/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Auto-freeze policy: master switch, delay, and never-freeze whitelist,
 * persisted at /data/misc/Nemuri/policy.json (readable by system_server across reboots).
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class FreezePolicyStore(private val xposed: XposedInterface) {
    @Volatile
    private var autoFreezeEnabled = false // default off for safety

    @Volatile
    private var freezeDelayMs = DEFAULT_DELAY_MS
    private val whitelist: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isEnabled(): Boolean = autoFreezeEnabled

    fun getDelayMs(): Long = freezeDelayMs

    fun isWhitelisted(pkg: String): Boolean = whitelist.contains(pkg)

    fun whitelistSnapshot(): Set<String> = HashSet(whitelist)

    fun apply(enabled: Boolean, delayMs: Long, newWhitelist: Set<String>) {
        autoFreezeEnabled = enabled
        freezeDelayMs = if (delayMs > 0) delayMs else DEFAULT_DELAY_MS
        whitelist.clear()
        whitelist.addAll(newWhitelist)
        save()
    }

    fun load() {
        try {
            val file = File(FILE)
            if (!file.exists()) {
                // First run: seed the default whitelist and persist a starting policy.json.
                whitelist.addAll(DEFAULT_WHITELIST)
                save()
                return
            }
            val root = JSONObject(file.readText())
            autoFreezeEnabled = root.optBoolean("autoFreezeEnabled", false)
            freezeDelayMs = root.optLong("freezeDelayMs", DEFAULT_DELAY_MS)
            whitelist.clear()
            val arr = root.optJSONArray("whitelist")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val pkg = arr.optString(i, null)
                    if (!pkg.isNullOrEmpty()) whitelist.add(pkg)
                }
            }
            xposed.log(
                Log.INFO, TAG,
                "Policy loaded: enabled=$autoFreezeEnabled delay=$freezeDelayMs whitelist=${whitelist.size}"
            )
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "Failed to load policy.json; using defaults", throwable)
        }
    }

    private fun save() {
        try {
            val dir = File(DIR)
            if (!dir.exists()) {
                // system_server creates this under /data/misc, inheriting system_data_file context.
                dir.mkdirs()
                dir.setReadable(false, false)
                dir.setReadable(true, true)
                dir.setWritable(false, false)
                dir.setWritable(true, true)
                dir.setExecutable(false, false)
                dir.setExecutable(true, true)
            }
            val root = JSONObject()
            root.put("version", 1)
            root.put("autoFreezeEnabled", autoFreezeEnabled)
            root.put("freezeDelayMs", freezeDelayMs)
            val arr = JSONArray()
            for (pkg in whitelist) arr.put(pkg)
            root.put("whitelist", arr)
            File(FILE).writeText(root.toString(2))
            xposed.log(
                Log.INFO, TAG,
                "Policy saved: enabled=$autoFreezeEnabled delay=$freezeDelayMs whitelist=${whitelist.size}"
            )
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "Failed to save policy.json", throwable)
        }
    }

    private companion object {
        const val TAG = "Nemuri"
        const val DIR = "/data/misc/Nemuri"
        const val FILE = "$DIR/policy.json"
        const val DEFAULT_DELAY_MS = 5000L

        // Seeded into a fresh policy.json as ordinary whitelist entries (not hard-coded exemptions):
        // apps that need a persistent background process for messages because they register no OEM
        // push service. The user can remove them like any other whitelist item.
        val DEFAULT_WHITELIST = setOf("com.tencent.mm") // WeChat
    }
}
