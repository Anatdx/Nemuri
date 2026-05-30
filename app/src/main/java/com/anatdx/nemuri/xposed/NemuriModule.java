package com.anatdx.nemuri.xposed;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.anatdx.nemuri.xposed.bridge.SystemServerRuntimeBridge;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

public final class NemuriModule extends XposedModule {
    private static final String TAG = "Nemuri";
    private final ConcurrentHashMap<String, AtomicInteger> hookHitCounters = new ConcurrentHashMap<>();
    private volatile SystemServerRuntimeBridge runtimeBridge;

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        log(
                Log.INFO,
                TAG,
                "Loaded in "
                        + param.getProcessName()
                        + ", systemServer="
                        + param.isSystemServer()
                        + ", api="
                        + getApiVersion()
                        + ", framework="
                        + getFrameworkName()
                        + " "
                        + getFrameworkVersion()
        );
    }

    @Override
    public void onSystemServerStarting(
            @NonNull XposedModuleInterface.SystemServerStartingParam param
    ) {
        log(Log.INFO, TAG, "System server scope is active; installing framework hook probes.");
        installSystemServerHooks(param.getClassLoader());
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) {
            return;
        }
        log(Log.DEBUG, TAG, "Package ready: " + param.getPackageName());
    }

    private void installSystemServerHooks(@NonNull ClassLoader classLoader) {
        runtimeBridge = new SystemServerRuntimeBridge(this, classLoader);
        hookActivityManagerServiceCapture(classLoader);
        hookRuntimeBinderPublish(classLoader);
        hookMethods(
                classLoader,
                "com.android.server.am.ActivityManagerService",
                "forceStopPackage",
                "killUid",
                "killPackageProcessesLSP"
        );
        hookMethods(
                classLoader,
                "com.android.server.am.ProcessList",
                "startProcessLocked"
        );
        hookMethods(
                classLoader,
                "com.android.server.am.CachedAppOptimizer",
                "freezeAppAsyncLSP",
                "unfreezeAppLSP",
                "freezeProcess",
                "unfreezeProcess",
                "setProcessFrozen"
        );
        hookMethods(
                classLoader,
                "com.android.server.wm.ActivityTaskManagerService",
                "moveTaskToBack",
                "removeTask"
        );
    }

    private void hookActivityManagerServiceCapture(@NonNull ClassLoader classLoader) {
        Class<?> targetClass;
        try {
            targetClass = Class.forName("com.android.server.am.ActivityManagerService", false, classLoader);
        } catch (Throwable tr) {
            log(Log.WARN, TAG, "Framework hook target missing: com.android.server.am.ActivityManagerService");
            return;
        }

        int installed = 0;
        for (Method method : targetClass.getDeclaredMethods()) {
            if (!"setSystemProcess".equals(method.getName())) {
                continue;
            }
            try {
                method.setAccessible(true);
                hook(method).intercept(new ActivityManagerCaptureHooker());
                installed++;
            } catch (Throwable tr) {
                log(Log.ERROR, TAG, "Failed to hook ActivityManagerService#setSystemProcess", tr);
            }
        }
        if (installed > 0) {
            log(Log.INFO, TAG, "Hook installed: ActivityManagerService#setSystemProcess (" + installed + " overloads)");
        }
    }

    private void hookRuntimeBinderPublish(@NonNull ClassLoader classLoader) {
        Class<?> targetClass;
        try {
            targetClass = Class.forName("com.android.server.am.ActivityManagerService", false, classLoader);
        } catch (Throwable tr) {
            log(Log.WARN, TAG, "Framework hook target missing: com.android.server.am.ActivityManagerService");
            return;
        }

        // setSystemProcess captures AMS too early to broadcast; publish once the system is
        // ready. Both hooks are guarded by the bridge so only the first one actually publishes.
        for (String methodName : new String[]{"systemReady", "finishBooting"}) {
            int installed = 0;
            for (Method method : targetClass.getDeclaredMethods()) {
                if (!methodName.equals(method.getName())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    hook(method).intercept(new RuntimeBinderPublishHooker());
                    installed++;
                } catch (Throwable tr) {
                    log(Log.ERROR, TAG, "Failed to hook ActivityManagerService#" + methodName + " for Binder publish", tr);
                }
            }
            if (installed > 0) {
                log(Log.INFO, TAG, "Hook installed for runtime Binder publish: ActivityManagerService#" + methodName + " (" + installed + " overloads)");
            }
        }
    }

    private void hookMethods(
            @NonNull ClassLoader classLoader,
            @NonNull String className,
            @NonNull String... methodNames
    ) {
        Class<?> targetClass;
        try {
            targetClass = Class.forName(className, false, classLoader);
        } catch (Throwable tr) {
            log(Log.WARN, TAG, "Framework hook target missing: " + className);
            return;
        }

        for (String methodName : methodNames) {
            int installed = 0;
            for (Method method : targetClass.getDeclaredMethods()) {
                if (!methodName.equals(method.getName())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    hook(method).intercept(new ProbeHooker(className + "#" + methodName));
                    installed++;
                } catch (Throwable tr) {
                    log(
                            Log.ERROR,
                            TAG,
                            "Failed to hook " + className + "#" + methodName + signatureOf(method),
                            tr
                    );
                }
            }
            if (installed > 0) {
                log(
                        Log.INFO,
                        TAG,
                        "Hook installed: " + className + "#" + methodName + " (" + installed + " overloads)"
                );
            }
        }
    }

    private String signatureOf(@NonNull Method method) {
        StringBuilder builder = new StringBuilder("(");
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(parameterTypes[i].getSimpleName());
        }
        return builder.append(")").toString();
    }

    private final class ProbeHooker implements XposedInterface.Hooker {
        private final String label;

        private ProbeHooker(@NonNull String label) {
            this.label = label;
        }

        @Override
        public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
            AtomicInteger counter = hookHitCounters.computeIfAbsent(label, ignored -> new AtomicInteger());
            int hit = counter.incrementAndGet();
            if (hit <= 5) {
                log(Log.DEBUG, TAG, "Framework hook hit [" + hit + "]: " + label);
            }
            return chain.proceed();
        }
    }

    private final class ActivityManagerCaptureHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            SystemServerRuntimeBridge bridge = runtimeBridge;
            if (bridge != null && chain.getThisObject() != null) {
                bridge.captureActivityManagerService(chain.getThisObject());
            }
            return result;
        }
    }

    private final class RuntimeBinderPublishHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            SystemServerRuntimeBridge bridge = runtimeBridge;
            if (bridge != null) {
                bridge.publishRuntimeBinder();
            }
            return result;
        }
    }

}
