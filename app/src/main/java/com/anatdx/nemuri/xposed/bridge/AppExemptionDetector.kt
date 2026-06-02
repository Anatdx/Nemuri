/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Observation layer: detects each background app's active freeze-policy exemptions.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

// Observation layer: computes each background app's active freeze-policy exemptions as a
// NemuriBridgeProtocol EXEMPT_* bitmask. Detection only -- never freezes. Location not wired yet.
class AppExemptionDetector(private val xposed: XposedInterface) {
    private val packageFlagCache = ConcurrentHashMap<String, Int>()

    /** Global freeze-relevant facts captured once per snapshot request. */
    class Snapshot internal constructor(
        val audioUids: Set<Int>,
        val micUids: Set<Int>,
        val vpnUids: Set<Int>,
        val inputMethodPackage: String?,
        val launcherPackage: String?,
        val accessibilityPackages: Set<String>,
    )

    fun snapshot(context: Context, vpnUids: Set<Int>): Snapshot = Snapshot(
        activePlaybackUids(context),
        activeRecordingUids(context),
        HashSet(vpnUids),
        defaultInputMethodPackage(context),
        currentLauncherPackage(context),
        enabledAccessibilityPackages(context),
    )

    fun flagsFor(context: Context, packageName: String, uid: Int, snapshot: Snapshot): Int {
        var flags = NemuriBridgeProtocol.EXEMPT_NONE
        if (NemuriBridgeProtocol.MANAGER_PACKAGE_NAME == packageName) {
            flags = flags or NemuriBridgeProtocol.EXEMPT_SELF
        }
        flags = flags or packageFlags(context, packageName)
        if (packageName == snapshot.inputMethodPackage) {
            flags = flags or NemuriBridgeProtocol.EXEMPT_INPUT_METHOD
        }
        if (packageName == snapshot.launcherPackage) {
            flags = flags or NemuriBridgeProtocol.EXEMPT_LAUNCHER
        }
        if (snapshot.accessibilityPackages.contains(packageName)) {
            flags = flags or NemuriBridgeProtocol.EXEMPT_ACCESSIBILITY
        }
        if (snapshot.audioUids.contains(uid)) {
            flags = flags or NemuriBridgeProtocol.EXEMPT_AUDIO
        }
        if (snapshot.micUids.contains(uid)) {
            flags = flags or NemuriBridgeProtocol.EXEMPT_MICROPHONE
        }
        if (snapshot.vpnUids.contains(uid)) {
            flags = flags or NemuriBridgeProtocol.EXEMPT_VPN
        }
        return flags
    }

    private fun packageFlags(context: Context, packageName: String): Int {
        packageFlagCache[packageName]?.let { return it }
        var flags = 0
        try {
            val info = context.packageManager
                .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val systemMask = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            if ((info.flags and systemMask) != 0) {
                flags = flags or NemuriBridgeProtocol.EXEMPT_SYSTEM
            }
            if (isXposedModule(info)) {
                flags = flags or NemuriBridgeProtocol.EXEMPT_XPOSED_MODULE
            }
        } catch (ignored: Throwable) {
            // Unknown package: leave flags empty rather than guessing.
        }
        packageFlagCache[packageName] = flags
        return flags
    }

    private fun isXposedModule(info: ApplicationInfo): Boolean {
        // Legacy Xposed declares it via manifest meta-data; cheap, check first.
        val meta = info.metaData
        if (meta != null && (meta.containsKey("xposedmodule") || meta.containsKey("xposedminversion"))) {
            return true
        }
        // Modern libxposed modules (e.g. HyperCeiler) carry no such meta-data; they declare
        // themselves via files inside the apk. Scan the apk for the new (META-INF/xposed/) and
        // old (assets/xposed_init) markers. Cached per package by the caller, so the apk is
        // opened at most once per package.
        val apkPath = info.sourceDir ?: return false
        return try {
            ZipFile(apkPath).use { zip ->
                zip.getEntry("META-INF/xposed/module.prop") != null ||
                    zip.getEntry("assets/xposed_init") != null
            }
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun activePlaybackUids(context: Context): Set<Int> {
        val uids = HashSet<Int>()
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return uids
            for (config in audioManager.activePlaybackConfigurations) {
                // Only count players that are actually playing, not idle/paused ones.
                val state = reflectInt(config, "getPlayerState")
                if (state == null || state != PLAYER_STATE_STARTED) continue
                val uid = reflectInt(config, "getClientUid")
                if (uid != null && uid > 0) uids.add(uid)
            }
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "Failed to read active playback configs", throwable)
        }
        return uids
    }

    private fun activeRecordingUids(context: Context): Set<Int> {
        val uids = HashSet<Int>()
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return uids
            for (config in audioManager.activeRecordingConfigurations) {
                val uid = reflectInt(config, "getClientUid")
                if (uid != null && uid > 0) uids.add(uid)
            }
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "Failed to read active recording configs", throwable)
        }
        return uids
    }

    private fun reflectInt(target: Any, method: String): Int? = try {
        val handle = target.javaClass.getMethod(method)
        handle.isAccessible = true
        handle.invoke(target) as? Int
    } catch (ignored: Throwable) {
        null
    }

    private fun defaultInputMethodPackage(context: Context): String? = try {
        val id = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
        )
        id?.let { ComponentName.unflattenFromString(it) }?.packageName
    } catch (ignored: Throwable) {
        null
    }

    private fun currentLauncherPackage(context: Context): String? = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager
            .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    } catch (ignored: Throwable) {
        null
    }

    private fun enabledAccessibilityPackages(context: Context): Set<String> = try {
        val value = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (TextUtils.isEmpty(value)) {
            emptySet()
        } else {
            val packages = HashSet<String>()
            for (entry in value.split(":")) {
                ComponentName.unflattenFromString(entry)?.let { packages.add(it.packageName) }
            }
            packages
        }
    } catch (ignored: Throwable) {
        emptySet()
    }

    private companion object {
        const val TAG = "Nemuri"
        const val PLAYER_STATE_STARTED = 2 // AudioPlaybackConfiguration.PLAYER_STATE_STARTED (@SystemApi)
    }
}
