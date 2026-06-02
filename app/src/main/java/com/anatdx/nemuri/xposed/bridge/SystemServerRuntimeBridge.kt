/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - system_server-side bridge: serves background-app snapshots to the manager over Binder.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class SystemServerRuntimeBridge(
    private val xposed: XposedInterface,
    private val classLoader: ClassLoader,
) {
    private val binderPublished = AtomicBoolean(false)
    private val runtimeBinder = RuntimeBinder()
    private val exemptionDetector = AppExemptionDetector(xposed)
    private val freezeController = FreezeController(xposed)
    private val vpnUids: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    val freezeEngine: FreezeEngine = FreezeEngine(xposed, freezeController, exemptionDetector, vpnUids)
    val binderUnfreezeCoordinator = BinderUnfreezeCoordinator(xposed, freezeEngine)

    @Volatile private var activityManagerService: Any? = null
    @Volatile private var mLruProcesses: Any? = null
    @Volatile private var systemContext: Context? = null
    @Volatile private var processNameField: Field? = null
    @Volatile private var processUidField: Field? = null
    @Volatile private var processPidField: Field? = null
    @Volatile private var processInfoField: Field? = null
    @Volatile private var processStateField: Field? = null
    @Volatile private var curProcStateField: Field? = null

    init {
        freezeEngine.setBridge(this)
    }

    fun captureActivityManagerService(instance: Any) {
        activityManagerService = instance
        systemContext = readContext(instance)
        mLruProcesses = readLruProcesses(instance)
        initProcessFields()
        systemContext?.let { freezeEngine.setContext(it) }
        // Publishing is intentionally deferred to boot completion. setSystemProcess runs
        // during startBootstrapServices, long before AMS#mProcessesReady, so calling
        // sendStickyBroadcast here throws "Cannot broadcast before boot completed".
        // NemuriModule drives publishRuntimeBinder() from a post-boot AMS hook instead.
    }

    /** Updated by NemuriModule's Vpn#updateState hook; consumed by the exemption detector. */
    fun onVpnStateChanged(uid: Int, connected: Boolean) {
        if (uid <= 0) return
        if (connected) vpnUids.add(uid) else vpnUids.remove(uid)
    }

    private fun readContext(instance: Any): Context? = try {
        readField(instance, "mContext") as? Context
    } catch (throwable: Throwable) {
        xposed.log(Log.WARN, TAG, "Failed to read ActivityManagerService context", throwable)
        null
    }

    private fun readLruProcesses(instance: Any): Any? {
        try {
            val processList = readField(instance, "mProcessList") ?: return null
            val lruProcesses = readField(processList, "mLruProcesses")
            if (lruProcesses is List<*>) {
                xposed.log(Log.INFO, TAG, "Runtime bridge captured ProcessList#mLruProcesses")
                return lruProcesses
            }
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "Failed to capture ProcessList#mLruProcesses", throwable)
        }
        return null
    }

    private fun initProcessFields() {
        try {
            val processRecordClass = Class.forName("com.android.server.am.ProcessRecord", false, classLoader)
            val processStateRecordClass = Class.forName("com.android.server.am.ProcessStateRecord", false, classLoader)
            processNameField = fieldOrNull(processRecordClass, "processName")
            processUidField = fieldOrNull(processRecordClass, "uid")
            processPidField = firstFieldOrNull(processRecordClass, "mPid", "pid")
            processInfoField = fieldOrNull(processRecordClass, "info")
            processStateField = fieldOrNull(processRecordClass, "mState")
            curProcStateField = fieldOrNull(processStateRecordClass, "mCurProcState")
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "Failed to initialize ProcessRecord reflection fields", throwable)
        }
    }

    // Public (package,uid) list for the engine's periodic sweep over current background apps.
    fun snapshotBackgroundApps(): List<BackgroundAppRef> =
        collectBackgroundApps().map { BackgroundAppRef(it.packageName, it.uid) }

    private fun collectBackgroundApps(): List<BackgroundApp> {
        var processes = mLruProcesses
        if (processes !is List<*>) {
            val ams = activityManagerService
            if (ams != null) {
                processes = readLruProcesses(ams)
                mLruProcesses = processes
            }
        }
        if (processes !is List<*>) return emptyList()

        val records: List<Any?>
        synchronized(processes) {
            records = ArrayList(processes)
        }

        // Group each app's non-visible processes by package. Any app with a user-visible process
        // is the foreground app and is dropped entirely; this keeps background media etc., which
        // run as foreground-service rather than as a visible process.
        val apps = LinkedHashMap<String, BackgroundApp>()
        val visiblePackages = HashSet<String>()
        for (index in records.indices.reversed()) {
            val record = records[index] ?: continue
            val snapshot = readSnapshot(record) ?: continue
            val pkg = snapshot.packageName
            if (!isApplicationUid(snapshot.uid) || pkg.isBlank()) {
                continue
            }
            if (snapshot.procState <= VISIBLE_PROC_STATE_MAX) {
                visiblePackages.add(pkg)
                continue
            }
            apps.getOrPut(pkg) { BackgroundApp(pkg, snapshot.uid) }.add(snapshot)
        }
        for (visiblePackage in visiblePackages) {
            apps.remove(visiblePackage)
        }
        return ArrayList(apps.values)
    }

    private fun readSnapshot(record: Any): RunningProcessSnapshot? {
        return try {
            val uid = readInt(processUidField, record, -1)
            val pid = readInt(processPidField, record, -1)
            var procState = -1
            val state = readObject(processStateField, record)
            if (state != null) {
                procState = readInt(curProcStateField, state, -1)
            }
            var processName = readString(processNameField, record)
            var packageName = readPackageName(record, uid)
            if (processName.isNullOrBlank()) processName = packageName
            if (packageName.isNullOrBlank()) packageName = processName
            val finalProcessName = processName
            val finalPackageName = packageName
            if (finalProcessName.isNullOrBlank() || finalPackageName.isNullOrBlank()) return null
            RunningProcessSnapshot(finalPackageName, finalProcessName, uid, pid, procState)
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "Failed to read ProcessRecord snapshot", throwable)
            null
        }
    }

    private fun readPackageName(record: Any, uid: Int): String? {
        val info = readObject(processInfoField, record)
        if (info is ApplicationInfo) {
            val packageName = info.packageName
            if (!packageName.isNullOrBlank()) return packageName
        }
        val context = systemContext ?: return null
        val packages = context.packageManager.getPackagesForUid(uid)
        return if (packages.isNullOrEmpty()) null else packages[0]
    }

    private fun isApplicationUid(uid: Int): Boolean {
        if (uid == Process.INVALID_UID) return false
        // Regular installed apps live in [10000, 19999]. This excludes the system uid
        // (1000) used by system_server / Settings / Phone, native daemons below 10000,
        // and isolated/sandbox processes above 19999 — i.e. the standalone processes
        // (zygote and friends) that are not really apps.
        val appId = uid % PER_USER_RANGE
        return appId in FIRST_APPLICATION_UID..LAST_APPLICATION_UID
    }

    private fun isCallerAllowed(callingUid: Int): Boolean {
        if (callingUid == Process.SYSTEM_UID) return true
        val context = systemContext ?: return false
        val packages = context.packageManager.getPackagesForUid(callingUid) ?: return false
        return packages.any { NemuriBridgeProtocol.MANAGER_PACKAGE_NAME == it }
    }

    @Throws(ReflectiveOperationException::class)
    private fun readField(instance: Any, name: String): Any? {
        val field = fieldOrNull(instance.javaClass, name) ?: throw NoSuchFieldException(name)
        return field.get(instance)
    }

    private fun firstFieldOrNull(type: Class<*>, vararg names: String): Field? {
        for (name in names) {
            fieldOrNull(type, name)?.let { return it }
        }
        return null
    }

    private fun fieldOrNull(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                val field = current.getDeclaredField(name)
                field.isAccessible = true
                return field
            } catch (ignored: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun readObject(field: Field?, instance: Any): Any? = try {
        field?.get(instance)
    } catch (ignored: Throwable) {
        null
    }

    private fun readString(field: Field?, instance: Any): String? = readObject(field, instance) as? String

    private fun readInt(field: Field?, instance: Any, fallback: Int): Int = try {
        field?.getInt(instance) ?: fallback
    } catch (ignored: Throwable) {
        fallback
    }

    fun publishRuntimeBinder() {
        if (!binderPublished.compareAndSet(false, true)) return
        try {
            val context = systemContext
            if (context == null) {
                binderPublished.set(false)
                xposed.log(Log.WARN, TAG, "Cannot publish Nemuri runtime Binder without system context")
                return
            }
            val intent = Intent(NemuriBridgeProtocol.ACTION_BINDER)
            val extras = Bundle()
            extras.putBinder(NemuriBridgeProtocol.EXTRA_BRIDGE_BINDER, runtimeBinder)
            intent.putExtras(extras)
            context.sendStickyBroadcast(intent)
            xposed.log(Log.INFO, TAG, "Published Nemuri runtime Binder sticky broadcast.")
            freezeEngine.onBoot() // load persisted auto-freeze policy once the system is ready
            binderUnfreezeCoordinator.startIfAvailable() // start Re-Kernel backend if present
        } catch (throwable: Throwable) {
            binderPublished.set(false)
            xposed.log(Log.WARN, TAG, "Failed to publish Nemuri runtime Binder sticky broadcast", throwable)
        }
    }

    private inner class RuntimeBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(NemuriBridgeProtocol.DESCRIPTOR)
                return true
            }
            if (code != NemuriBridgeProtocol.TRANSACTION_GET_BACKGROUND_PROCESSES &&
                code != NemuriBridgeProtocol.TRANSACTION_SET_FROZEN &&
                code != NemuriBridgeProtocol.TRANSACTION_SET_LOG_ENABLED &&
                code != NemuriBridgeProtocol.TRANSACTION_SET_POLICY
            ) {
                return super.onTransact(code, data, reply, flags)
            }

            data.enforceInterface(NemuriBridgeProtocol.DESCRIPTOR)
            val callingUid = getCallingUid()
            if (!isCallerAllowed(callingUid)) {
                throw SecurityException("Caller is not allowed to use Nemuri runtime bridge")
            }

            // Inside a transaction the calling identity is the manager app; clear it so privileged
            // queries (e.g. AudioManager active-playback uids) run as system_server instead of
            // coming back anonymized.
            val identityToken = clearCallingIdentity()
            try {
                if (code == NemuriBridgeProtocol.TRANSACTION_SET_FROZEN) {
                    val targetUid = data.readInt()
                    val frozen = data.readInt() != 0
                    // Never let the manager freeze itself (target == caller).
                    val ok = targetUid != callingUid && freezeController.setFrozen(targetUid, frozen)
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) NemuriBridgeProtocol.REPLY_SUCCESS else NemuriBridgeProtocol.REPLY_FAILURE)
                    return true
                }

                if (code == NemuriBridgeProtocol.TRANSACTION_SET_LOG_ENABLED) {
                    RuntimeLog.verbose = data.readInt() != 0
                    reply?.writeNoException()
                    reply?.writeInt(NemuriBridgeProtocol.REPLY_SUCCESS)
                    return true
                }

                if (code == NemuriBridgeProtocol.TRANSACTION_SET_POLICY) {
                    val enabled = data.readInt() != 0
                    val delayMs = data.readLong()
                    val binderUnfreeze = data.readInt() != 0
                    val n = maxOf(0, data.readInt())
                    val whitelist = HashSet<String>()
                    for (i in 0 until n) {
                        val pkg = data.readString()
                        if (!pkg.isNullOrEmpty()) whitelist.add(pkg)
                    }
                    val ok = freezeEngine.applyPolicy(enabled, delayMs, binderUnfreeze, whitelist)
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) NemuriBridgeProtocol.REPLY_SUCCESS else NemuriBridgeProtocol.REPLY_FAILURE)
                    return true
                }

                val apps = collectBackgroundApps()
                val context = systemContext
                val exemptionSnapshot =
                    if (context == null) null else exemptionDetector.snapshot(context, vpnUids)
                reply?.writeNoException()
                reply?.writeInt(apps.size)
                for (app in apps) {
                    val exemptionFlags = if (context != null && exemptionSnapshot != null) {
                        exemptionDetector.flagsFor(context, app.packageName, app.uid, exemptionSnapshot)
                    } else {
                        NemuriBridgeProtocol.EXEMPT_NONE
                    }
                    reply?.writeString(app.packageName)
                    reply?.writeInt(app.uid)
                    reply?.writeInt(app.aggregateProcState())
                    reply?.writeInt(exemptionFlags)
                    reply?.writeInt(if (freezeController.isFrozen(app.uid)) 1 else 0)
                    reply?.writeInt(app.processes.size)
                    for (process in app.processes) {
                        reply?.writeString(process.processName)
                        reply?.writeInt(process.pid)
                        reply?.writeInt(process.procState)
                    }
                }
                return true
            } finally {
                restoreCallingIdentity(identityToken)
            }
        }
    }

    private class RunningProcessSnapshot(
        val packageName: String,
        val processName: String,
        val uid: Int,
        val pid: Int,
        val procState: Int,
    )

    private class BackgroundApp(val packageName: String, val uid: Int) {
        val processes = ArrayList<RunningProcessSnapshot>()
        private var minProcState = Int.MAX_VALUE

        fun add(snapshot: RunningProcessSnapshot) {
            processes.add(snapshot)
            if (snapshot.procState < minProcState) minProcState = snapshot.procState
        }

        // Closest-to-foreground state among this app's background processes.
        fun aggregateProcState(): Int = if (minProcState == Int.MAX_VALUE) -1 else minProcState
    }

    private companion object {
        const val TAG = "Nemuri"
        // procState <= this = user-visible (TOP/BOUND_TOP); such apps are foreground, not monitor
        // targets. Foreground-service apps (e.g. background media) are above this and stay listed.
        const val VISIBLE_PROC_STATE_MAX = 3
        const val PER_USER_RANGE = 100000
        const val FIRST_APPLICATION_UID = 10000
        const val LAST_APPLICATION_UID = 19999
    }
}
