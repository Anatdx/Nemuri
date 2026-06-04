/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Embian binder-unfreeze backend. Embian is a kernel module like Re-Kernel but hidden:
 * no procfs, registration via a stealth prctl. Discover the netlink unit + register via
 * prctl (memfd-backed args), then listen for binder events targeting frozen apps and unfreeze.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.io.FileDescriptor
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EmbianClient(
    private val xposed: XposedInterface,
    private val onTargetUid: (uid: Int, reason: String) -> Unit,
    private val onFirstMessage: () -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var fd: FileDescriptor? = null
    @Volatile private var firstSeen = false
    private var thread: Thread? = null

    // embian present iff the stealth prctl reports a valid unit (no procfs to probe).
    fun isAvailable(): Boolean = discoverUnit() in NETLINK_MIN..NETLINK_MAX

    fun start() {
        if (running || thread != null) return
        // Named "system_server" so its kernel comm satisfies Embian's prctl gate (see loop()), and
        // so it blends in rather than standing out as a fingerprint thread in a hidden module.
        thread = Thread({ loop() }, COMM_SYSTEM_SERVER).apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, e -> xposed.log(Log.WARN, TAG, "Embian thread uncaught", e) }
            start()
        }
    }

    fun stop() {
        running = false
        try {
            fd?.let { Os.close(it) }
        } catch (ignored: Throwable) {
        }
    }

    private fun loop() {
        // Embian's prctl gate checks current->comm == "system_server" (a per-thread name). This
        // worker isn't the main thread, so adopt that comm first or discover/register get denied;
        // ART routes setName for the current thread through prctl(PR_SET_NAME), changing comm.
        Thread.currentThread().name = COMM_SYSTEM_SERVER
        val unit = discoverUnit()
        if (unit !in NETLINK_MIN..NETLINK_MAX) {
            xposed.log(Log.INFO, TAG, "Embian unavailable (prctl discover failed)")
            return
        }
        val descriptor = try {
            val d = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_DGRAM, unit)
            Os.bind(d, newNetlinkSocketAddress(0)) // kernel assigns portid
            d
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "Embian netlink bind failed; falling back", throwable)
            return
        }
        val portid = try {
            portIdOf(Os.getsockname(descriptor))
        } catch (throwable: Throwable) {
            -1
        }
        if (portid <= 0 || !registerClient(portid)) {
            xposed.log(Log.WARN, TAG, "Embian register failed (portid=$portid); falling back")
            try { Os.close(descriptor) } catch (ignored: Throwable) {}
            return
        }
        fd = descriptor
        running = true
        xposed.log(Log.INFO, TAG, "Embian netlink unit=$unit registered portid=$portid")
        val buf = ByteBuffer.allocate(8 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        var errors = 0
        while (running) {
            try {
                buf.clear()
                val len = Os.read(descriptor, buf)
                if (len <= 0) continue
                handleMessage(buf, len)
                errors = 0
            } catch (throwable: Throwable) {
                if (!running) break
                if (++errors > MAX_ERRORS) {
                    xposed.log(Log.WARN, TAG, "Embian netlink errored repeatedly; stopping", throwable)
                    break
                }
            }
        }
        try { Os.close(descriptor) } catch (ignored: Throwable) {}
    }

    // Netlink frame: nlmsghdr(16) + embian_netlink_msg(32) + payload(embian_binder_event).
    // EVENT(0x8003) with event_type TRANSACTION(1)/REPLY(2) -> unfreeze target_uid.
    private fun handleMessage(buf: ByteBuffer, len: Int) {
        if (len < NLMSG_HDRLEN + EMBIAN_MSG_LEN) return
        val msgBase = NLMSG_HDRLEN
        val type = buf.getShort(msgBase + 6).toInt() and 0xFFFF // embian_netlink_msg.type @ off 6
        if (!firstSeen) {
            firstSeen = true
            onFirstMessage()
        }
        if (type != NL_MSG_EVENT) return
        val payload = msgBase + EMBIAN_MSG_LEN
        if (len < payload + 24) return // event_type..target_uid
        val eventType = buf.getInt(payload) // embian_binder_event.event_type @ off 0
        if (eventType != EVENT_BINDER_TRANSACTION && eventType != EVENT_BINDER_REPLY) return
        val targetUid = buf.getInt(payload + 20) // target_uid @ off 20
        onTargetUid(targetUid, "Embian")
    }

    private fun discoverUnit(): Int = withArgsMemfd { fdArgs, addr ->
        callPrctl(PRCTL_CMD_GET_UNIT, addr)
        val args = readArgs(fdArgs)
        if (args.getInt(4) == 0) args.getInt(8) else -1 // status @4 ==0 -> netlink_unit @8
    } ?: -1

    private fun registerClient(portid: Int): Boolean = withArgsMemfd { fdArgs, addr ->
        val out = ByteBuffer.allocate(ARGS_LEN).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0, portid) // embian_prctl_args.portid @0
        Os.pwrite(fdArgs, out.array(), 0, ARGS_LEN, 0)
        callPrctl(PRCTL_CMD_REGISTER, addr)
        readArgs(fdArgs).getInt(4) == 0 // status @4 == 0
    } ?: false

    // Run [block] with a 16-byte memfd mmapped (MAP_SHARED) so the kernel's copy_to_user on the
    // prctl args is visible back via pread. Cleans up the mapping + fd.
    private fun <T> withArgsMemfd(block: (FileDescriptor, Long) -> T): T? {
        var memfd: FileDescriptor? = null
        var addr = 0L
        return try {
            memfd = Os.memfd_create("e", 0)
            Os.ftruncate(memfd, ARGS_LEN.toLong())
            addr = Os.mmap(0, ARGS_LEN.toLong(), OsConstants.PROT_READ or OsConstants.PROT_WRITE, OsConstants.MAP_SHARED, memfd, 0)
            block(memfd, addr)
        } catch (ignored: Throwable) {
            null
        } finally {
            if (addr != 0L) try { Os.munmap(addr, ARGS_LEN.toLong()) } catch (ignored: Throwable) {}
            if (memfd != null) try { Os.close(memfd) } catch (ignored: Throwable) {}
        }
    }

    private fun callPrctl(command: Long, argsAddr: Long) {
        try {
            Os.prctl(PRCTL_OPTION, PRCTL_MAGIC, command, argsAddr, 0L)
        } catch (ignored: Throwable) {
            // prctl returns EINVAL by design (disguise); the real result is in the args memory.
        }
    }

    private fun readArgs(memfd: FileDescriptor): ByteBuffer {
        val out = ByteBuffer.allocate(ARGS_LEN).order(ByteOrder.LITTLE_ENDIAN)
        Os.pread(memfd, out.array(), 0, ARGS_LEN, 0)
        return out
    }

    private fun portIdOf(addr: SocketAddress): Int {
        val m = addr.javaClass.methods.firstOrNull { it.name == "getPortId" && it.parameterTypes.isEmpty() }
        if (m != null) return (m.invoke(addr) as Int)
        // fallback: nlPortId field
        val f = addr.javaClass.getDeclaredField("nlPortId").apply { isAccessible = true }
        return f.getInt(addr)
    }

    private fun newNetlinkSocketAddress(port: Int): SocketAddress {
        val clazz = Class.forName("android.system.NetlinkSocketAddress")
        return try {
            clazz.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(port, 0) as SocketAddress
        } catch (ignored: NoSuchMethodException) {
            clazz.getConstructor(Int::class.javaPrimitiveType).newInstance(port) as SocketAddress
        }
    }

    private companion object {
        const val TAG = "Nemuri"
        const val NETLINK_MIN = 25
        const val NETLINK_MAX = 31
        const val MAX_ERRORS = 8
        const val NLMSG_HDRLEN = 16
        const val EMBIAN_MSG_LEN = 32 // sizeof(embian_netlink_msg): 7*u32 + 2*u16
        const val ARGS_LEN = 16       // sizeof(embian_prctl_args)
        const val PRCTL_OPTION = 0x454d4249 // int option for Os.prctl
        const val PRCTL_MAGIC = 0x45424941L
        const val PRCTL_CMD_GET_UNIT = 1L
        const val PRCTL_CMD_REGISTER = 2L
        const val COMM_SYSTEM_SERVER = "system_server" // kernel comm required by Embian's prctl gate
        const val NL_MSG_EVENT = 0x8003
        const val EVENT_BINDER_TRANSACTION = 1
        const val EVENT_BINDER_REPLY = 2
    }
}
