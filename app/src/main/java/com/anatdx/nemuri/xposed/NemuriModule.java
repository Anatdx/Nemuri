package com.anatdx.nemuri.xposed;

import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class NemuriModule extends XposedModule {
    private static final String TAG = "Nemuri";

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
        log(Log.INFO, TAG, "System server scope is active; freezer hooks can be installed here.");
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) {
            return;
        }
        log(Log.DEBUG, TAG, "Package ready: " + param.getPackageName());
    }
}
