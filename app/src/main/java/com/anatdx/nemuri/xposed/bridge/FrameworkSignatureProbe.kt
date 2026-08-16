/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Diagnostic: dumps the real signatures of the ANR and uid-frozen-state entry points on
 * the running framework, so hooks are written against this device rather than against AOSP memory.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier

// Vendor forks rename and re-shape these methods freely (HyperOS in particular), so the hook code
// must match what is actually present. Run once at boot, read with `logcat -s Nemuri:I`.
object FrameworkSignatureProbe {
    private const val TAG = "Nemuri"

    private val CLASSES = arrayOf(
        // ANR paths
        "com.android.server.am.AnrHelper",
        "com.android.server.am.AnrHelper\$AnrRecord",
        "com.android.server.am.AppErrors",
        "com.android.server.am.ProcessErrorStateRecord",
        // uid frozen-state reporting
        "com.android.server.am.CachedAppOptimizer",
        "com.android.server.am.ActivityManagerService",
        // vendor freeze stacks, for the neutralization work that follows
        "com.miui.server.greeze.GreezeManagerService",
        "com.miui.server.smartpower.PowerFrozenManager",
    )

    private val METHOD_FILTER = arrayOf(
        "appNotResponding", "deferAppNotResponding", "handleShowAnr", "startAnrConsumerIfNeeded",
        "reportOneUidFrozenStateChanged", "reportUidFrozenStateChanged", "postUidFrozenMessage",
        "onUidFrozenStateChanged", "setProcessFrozen", "freezeProcess", "unfreezeProcess",
        "freezeAppAsyncLSP", "unfreezeAppLSP", "useFreezer", "enableFreezer",
    )

    private val FIELD_FILTER = arrayOf(
        "mUidFrozenStateChangedCallbackList", "mUseFreezer", "mAnrRecords", "mApp", "mService",
        "mProcLock", "mMilletEnable", "mPowerMilletEnable", "hasGreeze",
    )

    fun run(xposed: XposedInterface, classLoader: ClassLoader) {
        xposed.log(Log.INFO, TAG, "===== framework signature probe =====")
        for (className in CLASSES) {
            val clazz = try {
                Class.forName(className, false, classLoader)
            } catch (ignored: Throwable) {
                xposed.log(Log.INFO, TAG, "[absent] $className")
                continue
            }
            xposed.log(Log.INFO, TAG, "[present] $className")

            for (method in clazz.declaredMethods) {
                if (METHOD_FILTER.none { it == method.name }) continue
                val params = method.parameterTypes.joinToString(", ") { it.simpleName }
                val mods = Modifier.toString(method.modifiers)
                xposed.log(
                    Log.INFO, TAG,
                    "    m: $mods ${method.returnType.simpleName} ${method.name}($params)"
                )
            }

            for (field in clazz.declaredFields) {
                if (FIELD_FILTER.none { it == field.name }) continue
                val mods = Modifier.toString(field.modifiers)
                xposed.log(
                    Log.INFO, TAG,
                    "    f: $mods ${field.type.simpleName} ${field.name}"
                )
            }
        }
        xposed.log(Log.INFO, TAG, "===== probe done =====")
    }
}
