/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - system_server-side bridge: serves background-app snapshots to the manager over Binder.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

public final class SystemServerRuntimeBridge {
    private static final String TAG = "Nemuri";
    // procState <= this = user-visible (TOP/BOUND_TOP); such apps are foreground, not monitor
    // targets. Foreground-service apps (e.g. background media) are above this and stay listed.
    private static final int VISIBLE_PROC_STATE_MAX = 3;
    private static final int PER_USER_RANGE = 100000;
    private static final int FIRST_APPLICATION_UID = 10000;
    private static final int LAST_APPLICATION_UID = 19999;

    private final XposedInterface xposed;
    private final ClassLoader classLoader;
    private final AtomicBoolean binderPublished = new AtomicBoolean(false);
    private final RuntimeBinder runtimeBinder = new RuntimeBinder();
    private final AppExemptionDetector exemptionDetector;
    private final FreezeController freezeController;
    private final Set<Integer> vpnUids = ConcurrentHashMap.newKeySet();

    private volatile Object activityManagerService;
    private volatile Object mLruProcesses;
    private volatile Context systemContext;
    private volatile Field processNameField;
    private volatile Field processUidField;
    private volatile Field processPidField;
    private volatile Field processInfoField;
    private volatile Field processStateField;
    private volatile Field curProcStateField;

    public SystemServerRuntimeBridge(
            @NonNull XposedInterface xposed,
            @NonNull ClassLoader classLoader
    ) {
        this.xposed = xposed;
        this.classLoader = classLoader;
        this.exemptionDetector = new AppExemptionDetector(xposed);
        this.freezeController = new FreezeController(xposed);
    }

    public void captureActivityManagerService(@NonNull Object instance) {
        activityManagerService = instance;
        systemContext = readContext(instance);
        mLruProcesses = readLruProcesses(instance);
        initProcessFields();
        // Publishing is intentionally deferred to boot completion. setSystemProcess runs
        // during startBootstrapServices, long before AMS#mProcessesReady, so calling
        // sendStickyBroadcast here throws "Cannot broadcast before boot completed".
        // NemuriModule drives publishRuntimeBinder() from a post-boot AMS hook instead.
    }

    /** Updated by NemuriModule's Vpn#updateState hook; consumed by the exemption detector. */
    public void onVpnStateChanged(int uid, boolean connected) {
        if (uid <= 0) {
            return;
        }
        if (connected) {
            vpnUids.add(uid);
        } else {
            vpnUids.remove(uid);
        }
    }

    private Context readContext(@NonNull Object instance) {
        try {
            Object context = readField(instance, "mContext");
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable throwable) {
            xposed.log(Log.WARN, TAG, "Failed to read ActivityManagerService context", throwable);
            return null;
        }
    }

    private Object readLruProcesses(@NonNull Object instance) {
        try {
            Object processList = readField(instance, "mProcessList");
            if (processList == null) {
                return null;
            }
            Object lruProcesses = readField(processList, "mLruProcesses");
            if (lruProcesses instanceof List<?>) {
                xposed.log(Log.INFO, TAG, "Runtime bridge captured ProcessList#mLruProcesses");
                return lruProcesses;
            }
        } catch (Throwable throwable) {
            xposed.log(Log.WARN, TAG, "Failed to capture ProcessList#mLruProcesses", throwable);
        }
        return null;
    }

    private void initProcessFields() {
        try {
            Class<?> processRecordClass = Class.forName(
                    "com.android.server.am.ProcessRecord",
                    false,
                    classLoader
            );
            Class<?> processStateRecordClass = Class.forName(
                    "com.android.server.am.ProcessStateRecord",
                    false,
                    classLoader
            );
            processNameField = fieldOrNull(processRecordClass, "processName");
            processUidField = fieldOrNull(processRecordClass, "uid");
            processPidField = firstFieldOrNull(processRecordClass, "mPid", "pid");
            processInfoField = fieldOrNull(processRecordClass, "info");
            processStateField = fieldOrNull(processRecordClass, "mState");
            curProcStateField = fieldOrNull(processStateRecordClass, "mCurProcState");
        } catch (Throwable throwable) {
            xposed.log(Log.WARN, TAG, "Failed to initialize ProcessRecord reflection fields", throwable);
        }
    }

    private List<BackgroundApp> collectBackgroundApps() {
        Object processes = mLruProcesses;
        if (!(processes instanceof List<?>)) {
            Object ams = activityManagerService;
            if (ams != null) {
                processes = readLruProcesses(ams);
                mLruProcesses = processes;
            }
        }
        if (!(processes instanceof List<?>)) {
            return Collections.emptyList();
        }

        List<?> lruProcesses = (List<?>) processes;
        List<Object> records;
        synchronized (lruProcesses) {
            records = new ArrayList<>(lruProcesses);
        }

        // Group each app's non-visible processes by package. Any app with a user-visible process
        // is the foreground app and is dropped entirely; this keeps background media etc., which
        // run as foreground-service rather than as a visible process.
        Map<String, BackgroundApp> apps = new LinkedHashMap<>();
        Set<String> visiblePackages = new HashSet<>();
        for (int index = records.size() - 1; index >= 0; index--) {
            Object record = records.get(index);
            if (record == null) {
                continue;
            }
            RunningProcessSnapshot snapshot = readSnapshot(record);
            if (snapshot == null
                    || !isApplicationUid(snapshot.uid)
                    || snapshot.packageName == null
                    || snapshot.packageName.isBlank()) {
                continue;
            }
            if (snapshot.procState <= VISIBLE_PROC_STATE_MAX) {
                visiblePackages.add(snapshot.packageName);
                continue;
            }
            apps.computeIfAbsent(
                    snapshot.packageName,
                    ignored -> new BackgroundApp(snapshot.packageName, snapshot.uid)
            ).add(snapshot);
        }
        for (String visiblePackage : visiblePackages) {
            apps.remove(visiblePackage);
        }
        return new ArrayList<>(apps.values());
    }

    private RunningProcessSnapshot readSnapshot(@NonNull Object record) {
        try {
            int uid = readInt(processUidField, record, -1);
            int pid = readInt(processPidField, record, -1);
            int procState = -1;
            Object state = readObject(processStateField, record);
            if (state != null) {
                procState = readInt(curProcStateField, state, -1);
            }
            String processName = readString(processNameField, record);
            String packageName = readPackageName(record, uid);
            if (processName == null || processName.isBlank()) {
                processName = packageName;
            }
            if (packageName == null || packageName.isBlank()) {
                packageName = processName;
            }
            if (processName == null || processName.isBlank()) {
                return null;
            }
            return new RunningProcessSnapshot(packageName, processName, uid, pid, procState);
        } catch (Throwable throwable) {
            xposed.log(Log.WARN, TAG, "Failed to read ProcessRecord snapshot", throwable);
            return null;
        }
    }

    private String readPackageName(@NonNull Object record, int uid) {
        Object info = readObject(processInfoField, record);
        if (info instanceof ApplicationInfo) {
            String packageName = ((ApplicationInfo) info).packageName;
            if (packageName != null && !packageName.isBlank()) {
                return packageName;
            }
        }

        Context context = systemContext;
        if (context == null) {
            return null;
        }
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        return packages == null || packages.length == 0 ? null : packages[0];
    }

    private boolean isApplicationUid(int uid) {
        if (uid == Process.INVALID_UID) {
            return false;
        }
        // Regular installed apps live in [10000, 19999]. This excludes the system uid
        // (1000) used by system_server / Settings / Phone, native daemons below 10000,
        // and isolated/sandbox processes above 19999 — i.e. the standalone processes
        // (zygote and friends) that are not really apps.
        int appId = uid % PER_USER_RANGE;
        return appId >= FIRST_APPLICATION_UID && appId <= LAST_APPLICATION_UID;
    }

    private boolean isCallerAllowed(int callingUid) {
        if (callingUid == Process.SYSTEM_UID) {
            return true;
        }
        Context context = systemContext;
        if (context == null) {
            return false;
        }
        String[] packages = context.getPackageManager().getPackagesForUid(callingUid);
        if (packages == null) {
            return false;
        }
        for (String packageName : packages) {
            if (NemuriBridgeProtocol.MANAGER_PACKAGE_NAME.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private Object readField(@NonNull Object instance, @NonNull String name) throws ReflectiveOperationException {
        Field field = fieldOrNull(instance.getClass(), name);
        if (field == null) {
            throw new NoSuchFieldException(name);
        }
        return field.get(instance);
    }

    private Field firstFieldOrNull(@NonNull Class<?> type, @NonNull String... names) {
        for (String name : names) {
            Field field = fieldOrNull(type, name);
            if (field != null) {
                return field;
            }
        }
        return null;
    }

    private Field fieldOrNull(@NonNull Class<?> type, @NonNull String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private Object readObject(Field field, @NonNull Object instance) {
        try {
            return field == null ? null : field.get(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String readString(Field field, @NonNull Object instance) {
        Object value = readObject(field, instance);
        return value instanceof String ? (String) value : null;
    }

    private int readInt(Field field, @NonNull Object instance, int fallback) {
        try {
            return field == null ? fallback : field.getInt(instance);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public void publishRuntimeBinder() {
        if (!binderPublished.compareAndSet(false, true)) {
            return;
        }
        try {
            Context context = systemContext;
            if (context == null) {
                binderPublished.set(false);
                xposed.log(Log.WARN, TAG, "Cannot publish Nemuri runtime Binder without system context");
                return;
            }
            Intent intent = new Intent(NemuriBridgeProtocol.ACTION_BINDER);
            Bundle extras = new Bundle();
            extras.putBinder(NemuriBridgeProtocol.EXTRA_BRIDGE_BINDER, runtimeBinder);
            intent.putExtras(extras);
            context.sendStickyBroadcast(intent);
            xposed.log(Log.INFO, TAG, "Published Nemuri runtime Binder sticky broadcast.");
        } catch (Throwable throwable) {
            binderPublished.set(false);
            xposed.log(Log.WARN, TAG, "Failed to publish Nemuri runtime Binder sticky broadcast", throwable);
        }
    }

    private final class RuntimeBinder extends Binder {
        @Override
        protected boolean onTransact(int code, @NonNull Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(NemuriBridgeProtocol.DESCRIPTOR);
                return true;
            }
            if (code != NemuriBridgeProtocol.TRANSACTION_GET_BACKGROUND_PROCESSES
                    && code != NemuriBridgeProtocol.TRANSACTION_SET_FROZEN) {
                return super.onTransact(code, data, reply, flags);
            }

            data.enforceInterface(NemuriBridgeProtocol.DESCRIPTOR);
            int callingUid = Binder.getCallingUid();
            if (!isCallerAllowed(callingUid)) {
                throw new SecurityException("Caller is not allowed to use Nemuri runtime bridge");
            }

            // Inside a transaction the calling identity is the manager app; clear it so privileged
            // queries (e.g. AudioManager active-playback uids) run as system_server instead of
            // coming back anonymized.
            long identityToken = Binder.clearCallingIdentity();
            try {
                if (code == NemuriBridgeProtocol.TRANSACTION_SET_FROZEN) {
                    int targetUid = data.readInt();
                    boolean frozen = data.readInt() != 0;
                    // Never let the manager freeze itself (target == caller).
                    boolean ok = targetUid != callingUid && freezeController.setFrozen(targetUid, frozen);
                    reply.writeNoException();
                    reply.writeInt(ok ? NemuriBridgeProtocol.REPLY_SUCCESS : NemuriBridgeProtocol.REPLY_FAILURE);
                    return true;
                }

                List<BackgroundApp> apps = collectBackgroundApps();
                Context context = systemContext;
                AppExemptionDetector.Snapshot exemptionSnapshot =
                        context == null ? null : exemptionDetector.snapshot(context, vpnUids);
                reply.writeNoException();
                reply.writeInt(apps.size());
                for (BackgroundApp app : apps) {
                    int exemptionFlags = (context != null && exemptionSnapshot != null)
                            ? exemptionDetector.flagsFor(context, app.packageName, app.uid, exemptionSnapshot)
                            : NemuriBridgeProtocol.EXEMPT_NONE;
                    reply.writeString(app.packageName);
                    reply.writeInt(app.uid);
                    reply.writeInt(app.aggregateProcState());
                    reply.writeInt(exemptionFlags);
                    reply.writeInt(freezeController.isFrozen(app.uid) ? 1 : 0);
                    reply.writeInt(app.processes.size());
                    for (RunningProcessSnapshot process : app.processes) {
                        reply.writeString(process.processName);
                        reply.writeInt(process.pid);
                        reply.writeInt(process.procState);
                    }
                }
                return true;
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }
    }

    private static final class RunningProcessSnapshot {
        private final String packageName;
        private final String processName;
        private final int uid;
        private final int pid;
        private final int procState;

        private RunningProcessSnapshot(
                String packageName,
                String processName,
                int uid,
                int pid,
                int procState
        ) {
            this.packageName = packageName;
            this.processName = processName;
            this.uid = uid;
            this.pid = pid;
            this.procState = procState;
        }
    }

    private static final class BackgroundApp {
        private final String packageName;
        private final int uid;
        private final List<RunningProcessSnapshot> processes = new ArrayList<>();
        private int minProcState = Integer.MAX_VALUE;

        private BackgroundApp(String packageName, int uid) {
            this.packageName = packageName;
            this.uid = uid;
        }

        private void add(RunningProcessSnapshot snapshot) {
            processes.add(snapshot);
            if (snapshot.procState < minProcState) {
                minProcState = snapshot.procState;
            }
        }

        // Closest-to-foreground state among this app's background processes.
        private int aggregateProcState() {
            return minProcState == Integer.MAX_VALUE ? -1 : minProcState;
        }
    }
}
