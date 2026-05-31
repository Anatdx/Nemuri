/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Per-app freeze policy model and its JSON config-file storage.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.data.apps

import android.content.Context
import com.anatdx.nemuri.R
import org.json.JSONObject
import java.io.File

class AppPolicyStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    val configPath: String
        get() = file.absolutePath

    fun loadAll(): Map<String, AppPolicy> {
        if (!file.exists()) {
            return emptyMap()
        }

        return runCatching {
            val packages = JSONObject(file.readText()).optJSONObject("packages") ?: JSONObject()
            buildMap {
                packages.keys().forEach { packageName ->
                    packages.optJSONObject(packageName)?.let { json ->
                        put(packageName, AppPolicy.fromJson(packageName, json))
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun save(policy: AppPolicy) {
        val policies = loadAll().toMutableMap()
        policies[policy.packageName] = policy
        writeAll(policies)
    }

    private fun writeAll(policies: Map<String, AppPolicy>) {
        val root = JSONObject()
        val packages = JSONObject()
        policies.toSortedMap().forEach { (packageName, policy) ->
            packages.put(packageName, policy.toJson())
        }
        root.put("version", 1)
        root.put("packages", packages)
        file.writeText(root.toString(2))
    }

    private companion object {
        const val FILE_NAME = "app_policies.json"
    }
}

data class AppPolicy(
    val packageName: String,
    val enabled: Boolean = false,
    val strategy: FreezeStrategy = FreezeStrategy.Smart,
    val allowWhileCharging: Boolean = true,
    val blockWakeups: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("strategy", strategy.name)
        .put("allowWhileCharging", allowWhileCharging)
        .put("blockWakeups", blockWakeups)

    companion object {
        fun fromJson(packageName: String, json: JSONObject): AppPolicy = AppPolicy(
            packageName = packageName,
            enabled = json.optBoolean("enabled", false),
            strategy = FreezeStrategy.entries.firstOrNull {
                it.name == json.optString("strategy")
            } ?: FreezeStrategy.Smart,
            allowWhileCharging = json.optBoolean("allowWhileCharging", true),
            blockWakeups = json.optBoolean("blockWakeups", false),
        )
    }
}

enum class FreezeStrategy(
    val labelRes: Int,
    val descriptionRes: Int,
) {
    Smart(R.string.strategy_smart, R.string.strategy_smart_description),
    Aggressive(R.string.strategy_aggressive, R.string.strategy_aggressive_description),
    Disabled(R.string.strategy_disabled, R.string.strategy_disabled_description),
}
