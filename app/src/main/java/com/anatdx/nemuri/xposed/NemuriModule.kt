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
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NemuriModule : XposedModule() {
    private val hookHitCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val activeHookIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val activeHookHandles = ConcurrentHashMap<String, XposedInterface.HookHandle>()

    @Volatile
    private var runtimeBridge: SystemServerRuntimeBridge? = null

    @Volatile
    private var systemServerClassLoader: ClassLoader? = null

    private val signatureProbeDone = AtomicBoolean(false)

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

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        val classLoader = systemServerClassLoader ?: return false
        val bridge = runtimeBridge ?: return false
        return try {
            val state = arrayOf(classLoader, bridge.snapshotAndStopForHotReload())
            param.setSavedInstanceState(state)
            log(Log.INFO, TAG, "Hot reload state saved; old runtime stopped")
            true
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "Failed to prepare hot reload", throwable)
            false
        }
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        val state = param.savedInstanceState as? Array<*>
        val classLoader = state?.getOrNull(0) as? ClassLoader
        val bridgeState = state?.getOrNull(1) as? Array<*>
        if (!param.isSystemServer || classLoader == null || bridgeState == null) {
            log(Log.ERROR, TAG, "Hot reload state is incomplete; removing old hooks")
            param.oldHookHandles.forEach { it.unhook() }
            return
        }

        try {
            systemServerClassLoader = classLoader
            runtimeBridge = SystemServerRuntimeBridge(this, classLoader).also {
                it.restoreAfterHotReload(bridgeState)
            }
            activeHookIds.clear()
            activeHookHandles.clear()
            for (handle in param.oldHookHandles) {
                val executable = handle.executable
                val hooker = hookerFor(executable)
                if (hooker == null) {
                    handle.unhook()
                    continue
                }
                val id = hookId(executable)
                val replacement = handle.replaceHook(hooker)
                activeHookIds.add(id)
                activeHookHandles[id] = replacement
            }
            installSystemServerHooks(classLoader)
            log(Log.INFO, TAG, "Hot reload completed: hooks=${activeHookIds.size}")
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "Hot reload failed; removing old hooks", throwable)
            activeHookHandles.values.forEach { runCatching { it.unhook() } }
            param.oldHookHandles.forEach { runCatching { it.unhook() } }
            activeHookIds.clear()
            activeHookHandles.clear()
            runtimeBridge?.let { runCatching { it.snapshotAndStopForHotReload() } }
            runtimeBridge = null
        }
    }

    private fun installSystemServerHooks(classLoader: ClassLoader) {
        systemServerClassLoader = classLoader
        if (runtimeBridge == null) runtimeBridge = SystemServerRuntimeBridge(this, classLoader)
        hookActivityManagerServiceCapture(classLoader)
        hookRuntimeBinderPublish(classLoader)
        hookVpnState(classLoader)
        hookCachedAppOptimizerControl(classLoader)
        hookActivityUsageStats(classLoader)
        hookBinderTrans(classLoader)
        hookAnr(classLoader)
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
                if (installHook(method, ActivityManagerCaptureHooker())) installed++
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
                    if (installHook(method, RuntimeBinderPublishHooker())) installed++
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
                if (installHook(method, VpnStateHooker())) installed++
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
                    if (installHook(method, UseFreezerHooker())) {
                        log(Log.INFO, TAG, "Hook installed: CachedAppOptimizer#useFreezer (force-disabled)")
                    }
                } else if (name == "enableFreezer" && method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType
                ) {
                    method.isAccessible = true
                    if (installHook(method, EnableFreezerHooker())) {
                        log(Log.INFO, TAG, "Hook installed: CachedAppOptimizer#enableFreezer (force-disabled)")
                    }
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
                if (installHook(method, ActivityUsageStatsHooker())) installed++
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
                if (installHook(method, ProcessStartHooker())) installed++
            } catch (tr: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook ProcessList#startProcessLocked", tr)
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Hook installed: ProcessList#startProcessLocked ($installed overloads)")
        }
    }

    // Temp-unfreeze on synchronous binder to a frozen app. reportBinderTrans on this device has
    // signature (int x5, boolean, long, long) and lives on the SmartPower classes (Greeze's didn't
    // see traffic, but we hook all present candidates harmlessly). arg0 = target uid.
    private fun hookBinderTrans(classLoader: ClassLoader) {
        for (className in BINDER_TRANS_CLASSES) {
            val targetClass = try {
                Class.forName(className, false, classLoader)
            } catch (tr: Throwable) {
                continue // not present on this device, fine
            }
            var installed = 0
            for (method in targetClass.declaredMethods) {
                if (method.name != "reportBinderTrans" || !isBinderTransSignature(method.parameterTypes)) {
                    continue
                }
                try {
                    method.isAccessible = true
                    if (installHook(method, BinderTransActionHooker())) installed++
                } catch (tr: Throwable) {
                    log(Log.ERROR, TAG, "Failed to hook $className#reportBinderTrans", tr)
                }
            }
            if (installed > 0) {
                log(Log.INFO, TAG, "Hook installed: $className#reportBinderTrans ($installed overloads)")
            }
        }
    }

    // reportBinderTrans(int, int, int, int, int, boolean, long, long)
    private fun isBinderTransSignature(p: Array<Class<*>>): Boolean {
        if (p.size != 8) return false
        val i = Int::class.javaPrimitiveType
        val z = Boolean::class.javaPrimitiveType
        val j = Long::class.javaPrimitiveType
        return p[0] == i && p[1] == i && p[2] == i && p[3] == i && p[4] == i &&
            p[5] == z && p[6] == j && p[7] == j
    }

    // Drop the ANRs that freezing causes. Verified signatures on this framework:
    //   AnrHelper#appNotResponding(ProcessRecord, TimeoutRecord)
    //   AnrHelper#appNotResponding(ProcessRecord, String, ApplicationInfo, String,
    //                             WindowProcessController, boolean, ExecutorService,
    //                             TimeoutRecord, boolean)
    //   AnrHelper#deferAppNotResponding(ProcessRecord, ..., long, boolean)
    //   AnrHelper$AnrRecord#appNotResponding(boolean)          -- receiver holds mApp
    //   ProcessErrorStateRecord#appNotResponding(...)          -- receiver holds mApp
    // Everything on AnrHelper carries the ProcessRecord in arg0; the other two carry it on the
    // instance. Hook both shapes so an ANR cannot slip through whichever path the framework takes.
    private fun hookAnr(classLoader: ClassLoader) {
        hookAnrOnClass(classLoader, "com.android.server.am.AnrHelper", argIndex = 0,
            "appNotResponding", "deferAppNotResponding")
        hookAnrOnClass(classLoader, "com.android.server.am.AnrHelper\$AnrRecord", argIndex = -1,
            "appNotResponding")
        hookAnrOnClass(classLoader, "com.android.server.am.ProcessErrorStateRecord", argIndex = -1,
            "appNotResponding")
    }

    /** @param argIndex where the ProcessRecord sits, or -1 when it hangs off the receiver. */
    private fun hookAnrOnClass(
        classLoader: ClassLoader,
        className: String,
        argIndex: Int,
        vararg methodNames: String,
    ) {
        val targetClass = try {
            Class.forName(className, false, classLoader)
        } catch (tr: Throwable) {
            log(Log.WARN, TAG, "ANR hook target missing: $className")
            return
        }
        for (methodName in methodNames) {
            var installed = 0
            for (method in targetClass.declaredMethods) {
                if (method.name != methodName) continue
                try {
                    method.isAccessible = true
                    if (installHook(method, AnrHooker("$className#$methodName", argIndex))) installed++
                } catch (tr: Throwable) {
                    log(Log.ERROR, TAG, "Failed to hook $className#$methodName${signatureOf(method)}", tr)
                }
            }
            if (installed > 0) {
                log(Log.INFO, TAG, "ANR hook installed: $className#$methodName ($installed overloads)")
            }
        }
    }

    private inner class AnrHooker(
        private val label: String,
        private val argIndex: Int,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            try {
                val suppressor = runtimeBridge?.anrSuppressor
                if (suppressor != null) {
                    val target = if (argIndex < 0) chain.thisObject else chain.args.getOrNull(argIndex)
                    if (suppressor.shouldSuppress(target)) {
                        if (RuntimeLog.verbose) {
                            log(Log.DEBUG, TAG, "ANR suppressed at $label")
                        }
                        // All hooked overloads return void, so skipping the original is enough --
                        // no ANR record is created and nothing downstream sees a null result.
                        return null
                    }
                }
            } catch (throwable: Throwable) {
                // Deciding failed: fall through to the real ANR rather than swallowing it blindly.
                log(Log.WARN, TAG, "ANR suppression check failed at $label", throwable)
            }
            return chain.proceed()
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
                    if (installHook(method, ProbeHooker("$className#$methodName"))) installed++
                } catch (tr: Throwable) {
                    log(Log.ERROR, TAG, "Failed to hook $className#$methodName${signatureOf(method)}", tr)
                }
            }
            if (installed > 0) {
                log(Log.INFO, TAG, "Hook installed: $className#$methodName ($installed overloads)")
            }
        }
    }

    private fun installHook(method: Method, hooker: XposedInterface.Hooker): Boolean {
        val id = hookId(method)
        if (!activeHookIds.add(id)) return false
        return try {
            activeHookHandles[id] = hook(method).setId(id).intercept(hooker)
            true
        } catch (throwable: Throwable) {
            activeHookIds.remove(id)
            throw throwable
        }
    }

    private fun hookId(executable: Executable): String = buildString {
        append(executable.declaringClass.name)
        append('#')
        append(executable.name)
        append('(')
        executable.parameterTypes.joinTo(this, separator = ",") { it.name }
        append(')')
    }

    private fun hookerFor(executable: Executable): XposedInterface.Hooker? {
        val method = executable as? Method ?: return null
        val className = method.declaringClass.name
        val methodName = method.name
        return when {
            className == CLASS_AMS && methodName == "setSystemProcess" ->
                ActivityManagerCaptureHooker()
            className == CLASS_AMS && methodName in RUNTIME_BINDER_METHODS ->
                RuntimeBinderPublishHooker()
            className == CLASS_VPN && methodName == "updateState" ->
                VpnStateHooker()
            className == CLASS_CACHED_APP_OPTIMIZER && methodName == "useFreezer" ->
                UseFreezerHooker()
            className == CLASS_CACHED_APP_OPTIMIZER && methodName == "enableFreezer" ->
                EnableFreezerHooker()
            className == CLASS_AMS && methodName == "updateActivityUsageStats" ->
                ActivityUsageStatsHooker()
            className == CLASS_PROCESS_LIST && methodName == "startProcessLocked" ->
                ProcessStartHooker()
            className in BINDER_TRANS_CLASSES && methodName == "reportBinderTrans" ->
                BinderTransActionHooker()
            className == CLASS_ANR_HELPER && methodName in ANR_HELPER_METHODS ->
                AnrHooker("$className#$methodName", 0)
            className in ANR_RECEIVER_CLASSES && methodName == "appNotResponding" ->
                AnrHooker("$className#$methodName", -1)
            PROBE_METHODS[className]?.contains(methodName) == true ->
                ProbeHooker("$className#$methodName")
            else -> null
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
            // Flip SIGNATURE_PROBE to dump the ANR/freeze entry points of whatever framework this is
            // running on. Driven from here rather than onSystemServerStarting: that runs before
            // logcat retains anything, so the output would be evicted before it can be read.
            if (SIGNATURE_PROBE && signatureProbeDone.compareAndSet(false, true)) {
                systemServerClassLoader?.let {
                    com.anatdx.nemuri.xposed.bridge.FrameworkSignatureProbe.run(this@NemuriModule, it)
                }
            }
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

    // reportBinderTrans(dstUid, ...): a binder transaction targeting dstUid. If dstUid is a frozen
    // app, temporarily unfreeze it so the call can be served (avoids the caller hanging to ANR).
    // arg0 = target uid (confirmed on-device). We don't gate on the oneway flag for now -- thawing
    // on a few oneway calls is harmless and safer than missing a sync one. Hot path: temporaryUnfreeze
    // returns immediately for non-frozen uids.
    private inner class BinderTransActionHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            try {
                val bridge = runtimeBridge ?: return result
                // A kernel backend (Embian/Re-Kernel), once active, owns binder unfreezing -- yield.
                if (bridge.binderUnfreezeCoordinator.isKernelBackendActive()) return result
                val dstUid = chain.args.firstOrNull() as? Int ?: return result
                bridge.freezeEngine.temporaryUnfreeze(dstUid, "Binder", BINDER_UNFREEZE_MS)
            } catch (throwable: Throwable) {
                // hot path: swallow
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
        const val BINDER_UNFREEZE_MS = 3000L
        const val CLASS_AMS = "com.android.server.am.ActivityManagerService"
        const val CLASS_PROCESS_LIST = "com.android.server.am.ProcessList"
        const val CLASS_CACHED_APP_OPTIMIZER = "com.android.server.am.CachedAppOptimizer"
        const val CLASS_VPN = "com.android.server.connectivity.Vpn"
        const val CLASS_ANR_HELPER = "com.android.server.am.AnrHelper"

        val RUNTIME_BINDER_METHODS = setOf("systemReady", "finishBooting")
        val ANR_HELPER_METHODS = setOf("appNotResponding", "deferAppNotResponding")
        val ANR_RECEIVER_CLASSES = setOf(
            "com.android.server.am.AnrHelper\$AnrRecord",
            "com.android.server.am.ProcessErrorStateRecord",
        )
        val PROBE_METHODS = mapOf(
            CLASS_AMS to setOf("forceStopPackage", "killUid", "killPackageProcessesLSP"),
            CLASS_CACHED_APP_OPTIMIZER to setOf(
                "freezeAppAsyncLSP",
                "unfreezeAppLSP",
                "freezeProcess",
                "unfreezeProcess",
                "setProcessFrozen",
            ),
            "com.android.server.wm.ActivityTaskManagerService" to setOf("moveTaskToBack", "removeTask"),
        )

        // Dev switch: dumps the framework's real ANR/freeze signatures at boot. Vendor forks reshape
        // these methods, so turn it on when porting to a new ROM and hook against what it reports.
        const val SIGNATURE_PROBE = false

        // HyperOS has both Greeze and SmartPower freeze stacks; reportBinderTrans exists on several
        // classes. Hook all present ones for observation, then narrow to the one with real traffic.
        val BINDER_TRANS_CLASSES = arrayOf(
            "com.miui.server.greeze.GreezeManagerService",
            "com.miui.server.greeze.GreezeManagerService\$TmpCallback",
            "com.android.server.am.AppStateManager\$PowerFrozenCallback",
            "com.android.server.am.SmartPowerService",
            "com.miui.server.smartpower.PowerFrozenManager",
        )
    }
}
