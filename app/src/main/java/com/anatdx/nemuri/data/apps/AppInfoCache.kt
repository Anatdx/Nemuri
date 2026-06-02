/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Persistent disk cache of app labels and icons so the app list shows instantly
 * instead of re-reading PackageManager on every launch.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.data.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppInfoCache(context: Context) {
    private val dir = File(context.cacheDir, "app_info_cache").apply { mkdirs() }
    private val iconsDir = File(dir, "icons").apply { mkdirs() }
    private val metaFile = File(dir, "meta.json")

    fun load(): List<InstalledAppInfo> {
        if (!metaFile.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(metaFile.readText())
            val arr = root.optJSONArray("apps") ?: JSONArray()
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val pkg = o.optString("pkg")
                    if (pkg.isEmpty()) continue
                    add(
                        InstalledAppInfo(
                            label = o.optString("label", pkg),
                            packageName = pkg,
                            system = o.optBoolean("system", false),
                            icon = readIcon(pkg),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(apps: List<InstalledAppInfo>) {
        runCatching {
            val arr = JSONArray()
            apps.forEach { app ->
                arr.put(
                    JSONObject()
                        .put("pkg", app.packageName)
                        .put("label", app.label)
                        .put("system", app.system)
                )
                app.icon?.let { writeIcon(app.packageName, it) }
            }
            metaFile.writeText(JSONObject().put("apps", arr).toString())
            pruneStaleIcons(apps.map { it.packageName }.toSet())
        }
    }

    private fun readIcon(pkg: String): Bitmap? = runCatching {
        val f = iconFile(pkg)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }.getOrNull()

    private fun writeIcon(pkg: String, bitmap: Bitmap) {
        runCatching {
            iconFile(pkg).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun pruneStaleIcons(livePackages: Set<String>) {
        iconsDir.listFiles()?.forEach { f ->
            val pkg = f.nameWithoutExtension
            if (pkg !in livePackages) f.delete()
        }
    }

    private fun iconFile(pkg: String) = File(iconsDir, "$pkg.png")
}
