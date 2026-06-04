/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Picks the binder-unfreeze backend: a kernel source (Embian, else Re-Kernel) if
 * available, else the Millet/Hans reportBinderTrans hook, else nothing. All feed
 * FreezeEngine.temporaryUnfreeze.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.io.File

class BinderUnfreezeCoordinator(
    private val xposed: XposedInterface,
    private val freezeEngine: FreezeEngine,
) {
    // A kernel backend (Embian/Re-Kernel) takes over once it delivers its first event; the
    // Millet/Hans hook path then yields. Single-direction flag, volatile for the binder hot path.
    @Volatile
    private var kernelBackendActive = false
    private var embianClient: EmbianClient? = null
    private var rekernelClient: RekernelClient? = null

    fun isKernelBackendActive(): Boolean = kernelBackendActive

    // Called at boot (system ready). Prefer Embian (hidden, no procfs), then Re-Kernel; the
    // Millet/Hans hook is the fallback until/unless a kernel backend delivers an event.
    fun startIfAvailable() {
        val onFirst = {
            if (!kernelBackendActive) {
                kernelBackendActive = true
                xposed.log(Log.INFO, TAG, "Kernel binder backend active; Millet/Hans hook path yielding")
            }
        }
        val feed = { uid: Int, reason: String -> freezeEngine.temporaryUnfreeze(uid, reason, BINDER_UNFREEZE_MS) }

        val embian = EmbianClient(xposed, feed, onFirst)
        if (embian.isAvailable()) {
            embianClient = embian.also { it.start() }
            xposed.log(Log.INFO, TAG, "Using Embian binder backend")
            return
        }
        if (File(PROC_REKERNEL).exists()) {
            rekernelClient = RekernelClient(xposed, feed, onFirst).also { it.start() }
            xposed.log(Log.INFO, TAG, "Using Re-Kernel binder backend")
            return
        }
        xposed.log(Log.INFO, TAG, "No kernel binder backend; using Millet/Hans hook")
    }

    private companion object {
        const val TAG = "Nemuri"
        const val PROC_REKERNEL = "/proc/rekernel"
        const val BINDER_UNFREEZE_MS = 3000L
    }
}
