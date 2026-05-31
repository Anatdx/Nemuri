package com.anatdx.nemuri.xposed.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

// Observation layer: computes each background app's active freeze-policy exemptions as a
// NemuriBridgeProtocol EXEMPT_* bitmask. Detection only -- never freezes. Location not wired yet.
final class AppExemptionDetector {
    private static final String TAG = "Nemuri";

    private final XposedInterface xposed;
    private final ConcurrentHashMap<String, Integer> packageFlagCache = new ConcurrentHashMap<>();

    AppExemptionDetector(@NonNull XposedInterface xposed) {
        this.xposed = xposed;
    }

    /** Global freeze-relevant facts captured once per snapshot request. */
    static final class Snapshot {
        private final Set<Integer> audioUids;
        private final Set<Integer> micUids;
        private final Set<Integer> vpnUids;
        private final String inputMethodPackage;
        private final String launcherPackage;
        private final Set<String> accessibilityPackages;

        private Snapshot(
                Set<Integer> audioUids,
                Set<Integer> micUids,
                Set<Integer> vpnUids,
                String inputMethodPackage,
                String launcherPackage,
                Set<String> accessibilityPackages
        ) {
            this.audioUids = audioUids;
            this.micUids = micUids;
            this.vpnUids = vpnUids;
            this.inputMethodPackage = inputMethodPackage;
            this.launcherPackage = launcherPackage;
            this.accessibilityPackages = accessibilityPackages;
        }
    }

    @NonNull
    Snapshot snapshot(@NonNull Context context, @NonNull Set<Integer> vpnUids) {
        return new Snapshot(
                activeAudioUids(context, "getActivePlaybackConfigurations"),
                activeAudioUids(context, "getActiveRecordingConfigurations"),
                new HashSet<>(vpnUids),
                defaultInputMethodPackage(context),
                currentLauncherPackage(context),
                enabledAccessibilityPackages(context)
        );
    }

    int flagsFor(@NonNull Context context, @NonNull String packageName, int uid, @NonNull Snapshot snapshot) {
        int flags = NemuriBridgeProtocol.EXEMPT_NONE;
        if (NemuriBridgeProtocol.MANAGER_PACKAGE_NAME.equals(packageName)) {
            flags |= NemuriBridgeProtocol.EXEMPT_SELF;
        }
        flags |= packageFlags(context, packageName);
        if (packageName.equals(snapshot.inputMethodPackage)) {
            flags |= NemuriBridgeProtocol.EXEMPT_INPUT_METHOD;
        }
        if (packageName.equals(snapshot.launcherPackage)) {
            flags |= NemuriBridgeProtocol.EXEMPT_LAUNCHER;
        }
        if (snapshot.accessibilityPackages.contains(packageName)) {
            flags |= NemuriBridgeProtocol.EXEMPT_ACCESSIBILITY;
        }
        if (snapshot.audioUids.contains(uid)) {
            flags |= NemuriBridgeProtocol.EXEMPT_AUDIO;
        }
        if (snapshot.micUids.contains(uid)) {
            flags |= NemuriBridgeProtocol.EXEMPT_MICROPHONE;
        }
        if (snapshot.vpnUids.contains(uid)) {
            flags |= NemuriBridgeProtocol.EXEMPT_VPN;
        }
        return flags;
    }

    private int packageFlags(@NonNull Context context, @NonNull String packageName) {
        Integer cached = packageFlagCache.get(packageName);
        if (cached != null) {
            return cached;
        }
        int flags = 0;
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            int systemMask = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            if ((info.flags & systemMask) != 0) {
                flags |= NemuriBridgeProtocol.EXEMPT_SYSTEM;
            }
            if (isXposedModule(info)) {
                flags |= NemuriBridgeProtocol.EXEMPT_XPOSED_MODULE;
            }
        } catch (Throwable ignored) {
            // Unknown package: leave flags empty rather than guessing.
        }
        packageFlagCache.put(packageName, flags);
        return flags;
    }

    private boolean isXposedModule(@NonNull ApplicationInfo info) {
        Bundle meta = info.metaData;
        return meta != null && (meta.containsKey("xposedmodule") || meta.containsKey("xposedminversion"));
    }

    private Set<Integer> activeAudioUids(@NonNull Context context, @NonNull String configMethod) {
        Set<Integer> uids = new HashSet<>();
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return uids;
            }
            Method method = AudioManager.class.getMethod(configMethod);
            Object result = method.invoke(audioManager);
            if (!(result instanceof List<?>)) {
                return uids;
            }
            for (Object config : (List<?>) result) {
                Integer uid = reflectClientUid(config);
                if (uid != null) {
                    uids.add(uid);
                }
            }
        } catch (Throwable throwable) {
            xposed.log(Log.WARN, TAG, "Failed to read audio config via " + configMethod, throwable);
        }
        return uids;
    }

    private Integer reflectClientUid(@NonNull Object config) {
        try {
            Method method = config.getClass().getMethod("getClientUid");
            method.setAccessible(true);
            Object value = method.invoke(config);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String defaultInputMethodPackage(@NonNull Context context) {
        try {
            String id = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            ComponentName component = id == null ? null : ComponentName.unflattenFromString(id);
            return component == null ? null : component.getPackageName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String currentLauncherPackage(@NonNull Context context) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = context.getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return resolved == null || resolved.activityInfo == null
                    ? null : resolved.activityInfo.packageName;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Set<String> enabledAccessibilityPackages(@NonNull Context context) {
        try {
            String value = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(value)) {
                return Collections.emptySet();
            }
            Set<String> packages = new HashSet<>();
            for (String entry : value.split(":")) {
                ComponentName component = ComponentName.unflattenFromString(entry);
                if (component != null) {
                    packages.add(component.getPackageName());
                }
            }
            return packages;
        } catch (Throwable ignored) {
            return Collections.emptySet();
        }
    }
}
