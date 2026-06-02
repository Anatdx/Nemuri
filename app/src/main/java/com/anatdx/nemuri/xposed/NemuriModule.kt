/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Xposed module entry; installs the system_server framework hooks.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.util.Log
import com.anatdx.nemuri.xposed.bridge.RuntimeLog
import com.anatdx.nemuri.xposed.bridge.SystemServerRuntimeBridge
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class NemuriModule : XposedModule() {
    private val hookHitCounters = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var runtimeBridge: SystemServerRuntimeBridge? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(
            Log.INFO, TAG,
            "Loaded in ${param.processName}, systemServer=${param.isSystemServer}" +
                ", api=$apiVersion, framework=$frameworkName $frameworkVersion"
        )
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        log(Log.INFO, TAG, "System server scope is active; installing framework hook probes.")
        installSystemServerHooks(param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return
        log(Log.DEBUG, TAG, "Package ready: ${param.packageName}")
    }

    private fun installSystemServerHooks(classLoader: ClassLoader) {
        runtimeBridge = SystemServerRuntimeBridge(this, classLoader)
        hookActivityManagerServiceCapture(classLoader)
        hookRuntimeBinderPublish(classLoader)
        hookVpnState(classLoader)
        hookCachedAppOptimizerControl(classLoader)
        hookActivityUsageStats(classLoader)
        hookMethods(
            classLoader,
            "com.android.server.am.ActivityManagerService",
            "forceStopPackage", "killUid", "killPackageProcessesLSP"
        )
        hookProcessStart(classLoader)
        hookMethods(
            classLoader,
            "com.android.server.am.CachedAppOptimizer",
            "freezeAppAsyncLSP", "unfreezeAppLSP", "freezeProcess", "unfreezeProcess", "setProcessFrozen"
        )
        hookMethods(
            classLoader,
            "com.android.server.wm.ActivityTaskManagerService",
            "moveTaskToBack", "removeTask"
        )
    }

    private fun hookActivityManagerServiceCapture(classLoader: ClassLoader) {
        val targetClass = try {
            Class.forName("com.android.server.am.ActivityManagerService", false, classLoader)
        } catch (tr: Throwable) {
            log(Log.WARN, TAG, "Framework hook target missing: com.android.server.am.ActivityManagerService")
            return
        }
        var installed = 0
        for (method in targetClass.declaredMethods) {
            if (method.name != "setSystemProcess") continue
            try {
                method.isAccessible = true
                hook(method).intercept(ActivityManagerCaptureHooker())
                installed++
            } catch (tr: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook ActivityManagerService#setSystemProcess", tr)
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Hook installed: ActivityManagerService#setSystemProcess ($installed overloads)")
        }
    }

    private fun hookRuntimeBinderPublish(classLoader: ClassLoader) {
        val targetClass = try {
            Class.forName("com.android.server.am.ActivityManagerService", false, classLoader)
        } catch (tr: Throwable) {
            log(Log.WARN, TAG, "Framework hook target missing: com.android.server.am.ActivityManagerService")
            return
        }
        // setSystemProcess captures AMS too early to broadcast; publish once the system is
        // ready. Both hooks are guarded by the bridge so only the first one actually publishes.
        for (methodName in arrayOf("systemReady", "finishBooting")) {
            var installed = 0
            for (method in targetClass.declaredMethods) {
                if (method.name != methodName) continue
                try {
                    method.isAccessible = true
                    hook(method).intercept(RuntimeBinderPublishHooker())
                    installed++
                } catch (tr: Throwable) {
                    log(Log.ERROR, TAG, "Failed to hook ActivityManagerService#$methodName for Binder publish", tr)
                }
            }
            if (installed > 0) {
                log(Log.INFO, TAG, "Hook installed for runtime Binder publish: ActivityManagerService#$methodName ($installed overloads)")
            }
        }
    }

    private fun hookVpnState(classLoader: ClassLoader) {
        val targetClass = try {
            Class.forName("com.android.server.connectivity.Vpn", false, classLoader)
        } catch (tr: Throwable) {
            log(Log.WARN, TAG, "Framework hook target missing: com.android.server.connectivity.Vpn")
            return
        }
        var installed = 0
        for (method in targetClass.declaredMethods) {
            if (method.name != "updateState") continue
            try {
                method.isAccessible = true
                hook(method).intercept(VpnStateHooker())
                installed++
            } catch (tr: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook Vpn#updateState", tr)
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Hook installed: Vpn#updateState ($installed overloads)")
        }
    }

    // Neutralize the framework's own freezer so Nemuri has exclusive control of cgroup.freeze.
    // Both methods return boolean (useFreezer ()Z, enableFreezer (Z)Z) -- the replacement MUST
    // return a Boolean, never null, or system_server NPEs on unboxing.
    private fun hookCachedAppOptimizerControl(classLoader: ClassLoader) {
        val targetClass = try {
            Class.forName("com.android.server.am.CachedAppOptimizer", false, classLoader)
        } catch (tr: Throwable) {
            log(Log.WARN, TAG, "Framework hook target missing: com.android.server.am.CachedAppOptimizer")
            return
        }
        for (method in targetClass.declaredMethods) {
            val name = method.name
            try {
                if (name == "useFreezer" && method.parameterTypes.isEmpty()) {
                    method.isAccessible = true
                    hook(method).intercept(UseFreezerHooker())
                    log(Log.INFO, TAG, "Hook installed: CachedAppOptimizer#useFreezer (force-disabled)")
                } else if (name == "enableFreezer" && method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType
                ) {
                    method.isAccessible = true
                    hook(method).intercept(EnableFreezerHooker())
                    log(Log.INFO, TAG, "Hook installed: CachedAppOptimizer#enableFreezer (force-disabled)")
                }
            } catch (tr: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook CachedAppOptimizer#$name", tr)
            }
        }
    }

    // Drives the auto-freeze engine. On this device updateActivityUsageStats lives on
    // ActivityManagerService (not ATMS). Real signature: updateActivityUsageStats(
    // ComponentName activity, int userId, int event, IBinder appToken, ComponentName taskRoot,
    // ActivityId activityId).
    private fun hookActivityUsageStats(classLoader: ClassLoader) {
        val targetClass = try {
            Class.forName("com.android.server.am.ActivityManagerService", false, classLoader)
        } catch (tr: Throwable) {
            log(Log.WARN, TAG, "Framework hook target missing: ActivityManagerService (usage stats)")
            return
        }
        var installed = 0
        for (method in targetClass.declaredMethods) {
            if (method.name != "updateActivityUsageStats") continue
            val params = method.parameterTypes
            // Match the ComponentName-first overload that carries the activity token.
            if (params.size < 4 || params[0] != ComponentName::class.java ||
                params[1] != Int::class.javaPrimitiveType || params[2] != Int::class.javaPrimitiveType ||
                params[3] != IBinder::class.java
            ) {
                continue
            }
            try {
                method.isAccessible = true
                hook(method).intercept(ActivityUsageStatsHooker())
                installed++
            } catch (tr: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook updateActivityUsageStats", tr)
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Hook installed: ActivityManagerService#updateActivityUsageStats ($installed overloads)")
        }
    }

    // Hook the top-level ProcessList#startProcessLocked(String, ApplicationInfo, ...) so every
    // process launch (boot autostart, pulled up, woken) feeds the engine immediately.
    private fun hookProcessStart(classLoader: ClassLoader) {
        val targetClass = try {
            Class.forName("com.android.server.am.ProcessList", false, classLoader)
        } catch (tr: Throwable) {
            log(Log.WARN, TAG, "Framework hook target missing: com.android.server.am.ProcessList")
            return
        }
        var installed = 0
        for (method in targetClass.declaredMethods) {
            if (method.name != "startProcessLocked") continue
            val params = method.parameterTypes
            if (params.size < 2 || params[0] != String::class.java || params[1] != ApplicationInfo::class.java) {
                continue
            }
            try {
                method.isAccessible = true
                hook(method).intercept(ProcessStartHooker())
                installed++
            } catch (tr: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook ProcessList#startProcessLocked", tr)
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Hook installed: ProcessList#startProcessLocked ($installed overloads)")
        }
    }

    private fun hookMethods(classLoader: ClassLoader, className: String, vararg methodNames: String) {
        val targetClass = try {
            Class.forName(className, false, classLoader)
        } catch (tr: Throwable) {
            log(Log.WARN, TAG, "Framework hook target missing: $className")
            return
        }
        for (methodName in methodNames) {
            var installed = 0
            for (method in targetClass.declaredMethods) {
                if (method.name != methodName) continue
                try {
                    method.isAccessible = true
                    hook(method).intercept(ProbeHooker("$className#$methodName"))
                    installed++
                } catch (tr: Throwable) {
                    log(Log.ERROR, TAG, "Failed to hook $className#$methodName${signatureOf(method)}", tr)
                }
            }
            if (installed > 0) {
                log(Log.INFO, TAG, "Hook installed: $className#$methodName ($installed overloads)")
            }
        }
    }

    private fun signatureOf(method: Method): String =
        method.parameterTypes.joinToString(", ", "(", ")") { it.simpleName }

    private inner class ProbeHooker(private val label: String) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val counter = hookHitCounters.computeIfAbsent(label) { AtomicInteger() }
            val hit = counter.incrementAndGet()
            if (RuntimeLog.verbose && hit <= 5) {
                log(Log.DEBUG, TAG, "Framework hook hit [$hit]: $label")
            }
            return chain.proceed()
        }
    }

    private inner class ActivityManagerCaptureHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val bridge = runtimeBridge
            if (bridge != null && chain.thisObject != null) {
                bridge.captureActivityManagerService(chain.thisObject!!)
            }
            return result
        }
    }

    private inner class RuntimeBinderPublishHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runtimeBridge?.publishRuntimeBinder()
            return result
        }
    }

    private inner class VpnStateHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            try {
                val bridge = runtimeBridge
                val vpn = chain.thisObject
                if (bridge != null && vpn != null && chain.args.isNotEmpty()) {
                    val state = chain.getArg(0).toString()
                    val ownerUid = readVpnOwnerUid(vpn)
                    if ("CONNECTED" == state) {
                        bridge.onVpnStateChanged(ownerUid, true)
                    } else if ("DISCONNECTED" == state || "FAILED" == state) {
                        bridge.onVpnStateChanged(ownerUid, false)
                    }
                }
            } catch (throwable: Throwable) {
                log(Log.WARN, TAG, "Vpn#updateState hook failed", throwable)
            }
            return result
        }
    }

    private fun readVpnOwnerUid(vpn: Any): Int {
        var current: Class<*>? = vpn.javaClass
        while (current != null) {
            try {
                val field = current.getDeclaredField("mOwnerUID")
                field.isAccessible = true
                return field.getInt(vpn)
            } catch (ignored: NoSuchFieldException) {
                current = current.superclass
            } catch (throwable: Throwable) {
                return -1
            }
        }
        return -1
    }

    // Forwards activity usage events to the freeze engine after the original runs.
    private inner class ActivityUsageStatsHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            try {
                val bridge = runtimeBridge
                val args = chain.args
                if (bridge != null && args.size >= 4 &&
                    args[0] is ComponentName && args[1] is Int && args[2] is Int && args[3] is IBinder
                ) {
                    bridge.freezeEngine.onActivityEvent(
                        args[0] as ComponentName,
                        args[1] as Int,
                        args[2] as Int,
                        args[3] as IBinder,
                    )
                }
            } catch (throwable: Throwable) {
                if (RuntimeLog.verbose) {
                    log(Log.WARN, TAG, "updateActivityUsageStats hook failed", throwable)
                }
            }
            return result
        }
    }

    // Feeds the freeze engine on every process launch. Light: just reads pkg/uid and hands off.
    private inner class ProcessStartHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            try {
                val bridge = runtimeBridge
                val args = chain.args
                if (bridge != null && args.size >= 2 && args[1] is ApplicationInfo) {
                    val info = args[1] as ApplicationInfo
                    if (info.packageName != null) {
                        bridge.freezeEngine.onProcessStarted(info.packageName, info.uid)
                    }
                }
            } catch (throwable: Throwable) {
                if (RuntimeLog.verbose) {
                    log(Log.WARN, TAG, "startProcessLocked hook failed", throwable)
                }
            }
            return result
        }
    }

    // useFreezer() -> Z. Replace with FALSE so the framework thinks its freezer is unavailable.
    private inner class UseFreezerHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any = java.lang.Boolean.FALSE
    }

    // enableFreezer(boolean) -> Z. Clear mUseFreezer (only if currently set, like Cirno) and
    // return FALSE -- never null, the method's return type is boolean.
    private inner class EnableFreezerHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any {
            try {
                val instance = chain.thisObject
                if (instance != null) {
                    val field = instance.javaClass.getDeclaredField("mUseFreezer")
                    field.isAccessible = true
                    if (field.getBoolean(instance)) {
                        field.setBoolean(instance, false)
                    }
                }
            } catch (throwable: Throwable) {
                log(Log.WARN, TAG, "Failed to clear mUseFreezer", throwable)
            }
            return java.lang.Boolean.FALSE
        }
    }

    private companion object {
        const val TAG = "Nemuri"
    }
}
