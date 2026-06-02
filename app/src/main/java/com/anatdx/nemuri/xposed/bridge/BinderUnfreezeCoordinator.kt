/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Picks the binder-unfreeze backend: Re-Kernel (netlink) if available, else the
 * Millet/Hans reportBinderTrans hook, else nothing. Both feed FreezeEngine.temporaryUnfreeze.
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
    // Re-Kernel takes over once it delivers its first message; the Millet/Hans hook path then
    // yields. Single-direction flag (set true on first Re-Kernel message), volatile for the
    // binder hot path to read. Reset to false if the Re-Kernel socket later dies.
    @Volatile
    private var rekernelActive = false
    private var rekernelClient: RekernelClient? = null

    fun isRekernelActive(): Boolean = rekernelActive

    // Called at boot (system ready). Starts the Re-Kernel netlink listener if /proc/rekernel
    // exists; the Millet/Hans hook (installed separately) is the fallback until/unless Re-Kernel
    // proves it works by delivering a message.
    fun startIfAvailable() {
        if (!File(PROC_REKERNEL).exists()) {
            xposed.log(Log.INFO, TAG, "No /proc/rekernel; using Millet/Hans binder hook backend")
            return
        }
        rekernelClient = RekernelClient(
            xposed = xposed,
            onTargetUid = { uid, reason -> freezeEngine.temporaryUnfreeze(uid, reason, BINDER_UNFREEZE_MS) },
            onFirstMessage = {
                if (!rekernelActive) {
                    rekernelActive = true
                    xposed.log(Log.INFO, TAG, "Re-Kernel active; Millet/Hans binder hook path yielding")
                }
            },
        ).also { it.start() }
    }

    private companion object {
        const val TAG = "Nemuri"
        const val PROC_REKERNEL = "/proc/rekernel"
        const val BINDER_UNFREEZE_MS = 3000L
    }
}
