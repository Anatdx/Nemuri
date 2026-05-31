/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Connects to the libxposed service and exposes module activation status to the UI.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed

import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

object XposedServiceStatus {
    private const val TAG = "Nemuri"
    private val started = AtomicBoolean(false)
    private val mutableState = mutableStateOf(ModuleStatus())

    val state: State<ModuleStatus> = mutableState

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Log.i(TAG, "Xposed service connected: ${service.frameworkName} ${service.frameworkVersion}")
                mutableState.value = ModuleStatus(
                    active = true,
                    frameworkName = service.frameworkName,
                    frameworkVersion = service.frameworkVersion,
                    apiVersion = service.apiVersion,
                    scope = runCatching { service.scope }.getOrDefault(emptyList()),
                )
            }

            override fun onServiceDied(service: XposedService) {
                Log.w(TAG, "Xposed service died: ${service.frameworkName} ${service.frameworkVersion}")
                mutableState.value = mutableState.value.copy(active = false)
            }
        })
    }
}

data class ModuleStatus(
    val active: Boolean = false,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val apiVersion: Int = 0,
    val scope: List<String> = emptyList(),
) {
    val frameworkLabel: String
        get() = listOf(frameworkName, frameworkVersion)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Unavailable" }

    val apiLabel: String
        get() = apiVersion.takeIf { it > 0 }?.toString() ?: "unknown"
}
