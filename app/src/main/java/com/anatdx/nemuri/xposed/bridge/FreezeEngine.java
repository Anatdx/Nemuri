/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Auto-freeze engine: schedules a freeze after an app leaves foreground and
 * thaws it immediately on return, rechecking exemptions/whitelist before freezing.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge;

import android.app.usage.UsageEvents;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

// Driven by ActivityTaskManagerService#updateActivityUsageStats events on a dedicated thread.
// Step 2 = dry-run: logs "would freeze" instead of writing cgroup.freeze. setDryRun(false) in
// Step 3 enables real freezing. Engine exceptions are swallowed so system_server is never harmed.
public final class FreezeEngine {
    private static final String TAG = "Nemuri";
    private static final int MSG_FREEZE = 0;
    private static final int MSG_SWEEP = 1;
    // UsageEvents.Event.ACTIVITY_DESTROYED is @hide; use its literal value (ACTIVITY_STOPPED + 1).
    private static final int ACTIVITY_DESTROYED = 24;
    // Low-frequency safety-net sweep. The process-start hook handles autostart/woken apps in real
    // time; this only catches anything the hooks might have missed, so it can be infrequent.
    private static final long SWEEP_FIRST_DELAY_MS = 60_000L;
    private static final long SWEEP_INTERVAL_MS = 300_000L;

    private final XposedInterface xposed;
    private final FreezeController freezeController;
    private final AppExemptionDetector exemptionDetector;
    private final Set<Integer> vpnUids;
    private final FreezePolicyStore policyStore;
    private final Handler handler;
    private final ConcurrentHashMap<String, AppFreezeState> states = new ConcurrentHashMap<>();
    private final Set<String> engineFrozenKeys = ConcurrentHashMap.newKeySet();

    private volatile Context context;
    private volatile boolean dryRun = false;
    private volatile SystemServerRuntimeBridge bridge;

    public FreezeEngine(
            @NonNull XposedInterface xposed,
            @NonNull FreezeController freezeController,
            @NonNull AppExemptionDetector exemptionDetector,
            @NonNull Set<Integer> vpnUids
    ) {
        this.xposed = xposed;
        this.freezeController = freezeController;
        this.exemptionDetector = exemptionDetector;
        this.vpnUids = vpnUids;
        this.policyStore = new FreezePolicyStore(xposed);

        HandlerThread thread = new HandlerThread("Nemuri-Freezer");
        thread.setUncaughtExceptionHandler((t, e) ->
                xposed.log(Log.ERROR, TAG, "Freezer thread uncaught", e));
        thread.start();
        this.handler = new Handler(thread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    if (msg.what == MSG_FREEZE && msg.obj instanceof String) {
                        freezer((String) msg.obj);
                    } else if (msg.what == MSG_SWEEP) {
                        sweepBackgroundApps();
                        // reschedule the next sweep
                        sendMessageDelayed(obtainMessage(MSG_SWEEP), SWEEP_INTERVAL_MS);
                    }
                } catch (Throwable throwable) {
                    xposed.log(Log.WARN, TAG, "handleMessage failed", throwable);
                }
            }
        };
    }

    void setContext(@NonNull Context context) {
        this.context = context;
    }

    void setBridge(@NonNull SystemServerRuntimeBridge bridge) {
        this.bridge = bridge;
    }

    void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    // Called at boot completion: load persisted policy and clear any stale frozen apps left in
    // cgroup from a previous run (the engine re-drives freezing from fresh activity events, so
    // nothing should start out frozen -- this recovers anything stuck across a framework reload).
    void onBoot() {
        policyStore.load();
        thawAllFrozenApps();
        handler.removeMessages(MSG_SWEEP);
        handler.sendMessageDelayed(handler.obtainMessage(MSG_SWEEP), SWEEP_FIRST_DELAY_MS);
    }

    private void thawAllFrozenApps() {
        try {
            freezeController.thawAllFrozen();
        } catch (Throwable throwable) {
            xposed.log(Log.WARN, TAG, "thawAllFrozenApps failed", throwable);
        }
    }

    boolean applyPolicy(boolean enabled, long delayMs, @NonNull Set<String> whitelist) {
        boolean wasEnabled = policyStore.isEnabled();
        policyStore.apply(enabled, delayMs, whitelist);
        if (wasEnabled && !enabled) {
            thawAllEngineFrozen(); // turning the engine off releases everything it froze
        }
        return true;
    }

    // From the ProcessList#startProcessLocked hook: a process is being (re)started. Schedule a
    // freeze so apps launched by any path (boot autostart, pulled up by others, woken) get caught
    // immediately, not only by the periodic sweep. freezer() does the full recheck on fire.
    // Kept extremely light: this runs in the AMS lock on a hot path; real work is on our thread.
    public void onProcessStarted(String packageName, int uid) {
        if (!policyStore.isEnabled()
                || packageName == null
                || packageName.isEmpty()
                || NemuriBridgeProtocol.MANAGER_PACKAGE_NAME.equals(packageName)
                || !freezeController.isAppUid(uid)
                || policyStore.isWhitelisted(packageName)) {
            return;
        }
        String key = (uid / 100000) + "#" + packageName;
        AppFreezeState state = states.get(key);
        if (state != null && state.isVisible()) {
            return;
        }
        if (!handler.hasMessages(MSG_FREEZE, key)) {
            handler.sendMessageDelayed(handler.obtainMessage(MSG_FREEZE, key), policyStore.getDelayMs());
        }
    }

    // From the ATMS hook. activity may be null for some events; event is a UsageEvents.Event.
    public void onActivityEvent(ComponentName activity, int userId, int event, IBinder token) {
        if (activity == null || token == null) {
            return;
        }
        String pkg = activity.getPackageName();
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        String key = userId + "#" + pkg;
        AppFreezeState state = states.computeIfAbsent(key, ignored -> new AppFreezeState());

        boolean transitioned;
        if (event == UsageEvents.Event.ACTIVITY_RESUMED || event == UsageEvents.Event.ACTIVITY_PAUSED) {
            transitioned = state.addActivity(token);
        } else if (event == UsageEvents.Event.ACTIVITY_STOPPED
                || event == ACTIVITY_DESTROYED) {
            transitioned = state.removeActivity(token);
        } else {
            return; // other events don't affect visibility
        }

        if (!transitioned) {
            return;
        }
        if (state.isVisible()) {
            // back to foreground: cancel pending freeze, thaw now
            handler.removeMessages(MSG_FREEZE, key);
            thawNow(key, pkg, userId);
            if (RuntimeLog.verbose) {
                xposed.log(Log.DEBUG, TAG, "foreground " + pkg);
            }
        } else {
            // went to background: schedule delayed freeze
            if (!handler.hasMessages(MSG_FREEZE, key)) {
                handler.sendMessageDelayed(handler.obtainMessage(MSG_FREEZE, key), policyStore.getDelayMs());
                if (RuntimeLog.verbose) {
                    xposed.log(Log.DEBUG, TAG, "background " + pkg + " -> freeze in " + policyStore.getDelayMs() + "ms");
                }
            }
        }
    }

    // Periodic catch-all: schedule a freeze for any background app that has no pending timer and
    // isn't frozen yet. freezer() does the full recheck when the timer fires, so this only needs
    // to ensure a timer exists. Covers boot-autostarted apps that never sent an activity event.
    private void sweepBackgroundApps() {
        if (!policyStore.isEnabled()) {
            return;
        }
        SystemServerRuntimeBridge b = bridge;
        if (b == null) {
            return;
        }
        for (BackgroundAppRef ref : b.snapshotBackgroundApps()) {
            if (ref.packageName == null
                    || NemuriBridgeProtocol.MANAGER_PACKAGE_NAME.equals(ref.packageName)
                    || policyStore.isWhitelisted(ref.packageName)
                    || !freezeController.isAppUid(ref.uid)
                    || freezeController.isFrozen(ref.uid)) {
                continue;
            }
            String key = (ref.uid / 100000) + "#" + ref.packageName;
            AppFreezeState state = states.get(key);
            if (state != null && state.isVisible()) {
                continue; // currently foreground
            }
            if (!handler.hasMessages(MSG_FREEZE, key)) {
                handler.sendMessageDelayed(handler.obtainMessage(MSG_FREEZE, key), policyStore.getDelayMs());
            }
        }
    }

    private void freezer(@NonNull String key) {
        if (!policyStore.isEnabled()) {
            return;
        }
        String pkg = packageOf(key);
        int userId = userIdOf(key);
        if (NemuriBridgeProtocol.MANAGER_PACKAGE_NAME.equals(pkg)) {
            return; // never freeze ourselves
        }
        if (policyStore.isWhitelisted(pkg)) {
            return;
        }
        AppFreezeState state = states.get(key);
        if (state != null && state.isVisible()) {
            return; // came back to foreground while queued
        }
        Context ctx = context;
        if (ctx == null) {
            return;
        }
        int uid = resolveUid(ctx, pkg, userId);
        if (!freezeController.isAppUid(uid)) {
            return;
        }
        int flags = exemptionDetector.flagsFor(ctx, pkg, uid, exemptionDetector.snapshot(ctx, vpnUids));
        if (flags != NemuriBridgeProtocol.EXEMPT_NONE) {
            return; // audio/mic/vpn/ime/launcher/a11y/system/xposed/self -> skip
        }
        if (dryRun) {
            xposed.log(Log.INFO, TAG, "would freeze " + pkg + " uid=" + uid);
            return;
        }
        if (freezeController.setFrozen(uid, true)) {
            engineFrozenKeys.add(key);
        }
    }

    // Always thaw on return-to-foreground based on the ACTUAL cgroup state, not on whether the
    // engine remembers freezing it. engineFrozenKeys is in-memory and lost across a framework
    // reload, while cgroup.freeze persists -- relying on it would leave apps frozen-stuck after a
    // reload. Checking the real state also recovers anything left frozen for any reason.
    private void thawNow(@NonNull String key, @NonNull String pkg, int userId) {
        engineFrozenKeys.remove(key);
        Context ctx = context;
        if (ctx == null) {
            return;
        }
        int uid = resolveUid(ctx, pkg, userId);
        if (freezeController.isAppUid(uid) && freezeController.isFrozen(uid)) {
            freezeController.setFrozen(uid, false);
        }
    }

    private void thawAllEngineFrozen() {
        Context ctx = context;
        for (String key : engineFrozenKeys) {
            engineFrozenKeys.remove(key);
            if (ctx != null) {
                int uid = resolveUid(ctx, packageOf(key), userIdOf(key));
                if (freezeController.isAppUid(uid)) {
                    freezeController.setFrozen(uid, false);
                }
            }
        }
    }

    private int resolveUid(@NonNull Context ctx, @NonNull String pkg, int userId) {
        try {
            int base = ctx.getPackageManager().getPackageUid(pkg, 0);
            int appId = base % 100000;
            return userId * 100000 + appId;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String packageOf(@NonNull String key) {
        int idx = key.indexOf('#');
        return idx >= 0 ? key.substring(idx + 1) : key;
    }

    private int userIdOf(@NonNull String key) {
        int idx = key.indexOf('#');
        try {
            return idx >= 0 ? Integer.parseInt(key.substring(0, idx)) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
