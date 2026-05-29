package com.anatdx.nemuri.data.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val system: Boolean,
    val icon: Bitmap?,
)

object AppRepository {
    fun loadInstalledApps(context: Context): List<InstalledAppInfo> {
        val packageManager = context.packageManager
        val iconSizePx = (context.resources.displayMetrics.density * ICON_SIZE_DP).toInt()
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        return apps
            .map { app ->
                InstalledAppInfo(
                    label = app.loadLabel(packageManager).toString(),
                    packageName = app.packageName,
                    system = app.isSystemApp,
                    icon = runCatching {
                        packageManager
                            .getApplicationIcon(app.packageName)
                            .toBitmap(iconSizePx, iconSizePx)
                    }.getOrNull(),
                )
            }
            .sortedWith(
                compareBy<InstalledAppInfo> { it.label.lowercase() }
                    .thenBy { it.packageName }
            )
    }

    private const val ICON_SIZE_DP = 42
}

private val ApplicationInfo.isSystemApp: Boolean
    get() = flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
        flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
