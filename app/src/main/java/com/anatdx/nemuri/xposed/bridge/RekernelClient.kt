/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Re-Kernel binder-unfreeze backend. Listens on the Re-Kernel netlink unit for
 * "type=Binder,...,target=<uid>" events and temp-unfreezes the target. No root needed
 * (android.system.Os netlink socket); only NetlinkSocketAddress needs reflection.
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
import java.io.File
import java.io.FileDescriptor
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class RekernelClient(
    private val xposed: XposedInterface,
    private val onTargetUid: (uid: Int, reason: String) -> Unit,
    private val onFirstMessage: () -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var fd: FileDescriptor? = null
    @Volatile private var firstSeen = false
    private var thread: Thread? = null

    fun start() {
        if (running || thread != null) return
        thread = Thread({ loop() }, "Nemuri-Rekernel").apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, e -> xposed.log(Log.WARN, TAG, "Rekernel thread uncaught", e) }
            start()
        }
    }

    fun stop() {
        running = false
        try {
            fd?.let { Os.close(it) } // unblocks Os.read with EBADF
        } catch (ignored: Throwable) {
        }
    }

    private fun loop() {
        val unit = resolveUnit()
        val descriptor = try {
            val d = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_DGRAM, unit)
            Os.setsockoptInt(d, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 64 * 1024)
            Os.bind(d, newNetlinkSocketAddress(NETLINK_PORT))
            d
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, TAG, "Re-Kernel netlink blocked (SELinux/errno/reflect); falling back to Millet", throwable)
            return
        }
        fd = descriptor
        running = true
        xposed.log(Log.INFO, TAG, "Re-Kernel netlink listening on unit $unit")
        // Tell the kernel to drop the /proc/rekernel node now that we're connected -- otherwise it
        // lingers as a detection fingerprint. The socket stays bound and keeps receiving.
        removeProcNode(descriptor)
        val buf = ByteBuffer.allocate(8 * 1024)
        var errors = 0
        while (running) {
            try {
                buf.clear()
                val len = Os.read(descriptor, buf)
                if (len <= 0) continue
                handleMessage(String(buf.array(), 0, len, StandardCharsets.UTF_8))
                errors = 0
            } catch (throwable: Throwable) {
                if (!running) break // stop() closed the fd
                if (++errors > MAX_ERRORS) {
                    xposed.log(Log.WARN, TAG, "Re-Kernel netlink errored repeatedly; stopping", throwable)
                    break
                }
            }
        }
        try {
            Os.close(descriptor)
        } catch (ignored: Throwable) {
        }
    }

    // "type=Binder,bindertype=transaction,oneway=0,target=10234;" -> temp-unfreeze target.
    // oneway is not gated (matches the Millet hook path; thawing a few oneway calls is harmless).
    private fun handleMessage(raw: String) {
        val start = raw.indexOf("type")
        val end = raw.lastIndexOf(';')
        if (start < 0 || end <= start) return
        val params = HashMap<String, String>()
        for (kv in raw.substring(start, end).split(",")) {
            val eq = kv.indexOf('=')
            if (eq > 0) params[kv.substring(0, eq).trim()] = kv.substring(eq + 1).trim()
        }
        if (params["type"] != "Binder") return
        if (!firstSeen) {
            firstSeen = true
            onFirstMessage()
        }
        val target = params["target"]?.toIntOrNull() ?: return
        onTargetUid(target, "Rekernel")
    }

    private fun resolveUnit(): Int = try {
        File(PROC_REKERNEL).listFiles()?.firstOrNull()?.name?.toIntOrNull() ?: DEFAULT_UNIT
    } catch (ignored: Throwable) {
        DEFAULT_UNIT
    }

    // Ask the kernel to remove /proc/rekernel now that we're connected (anti-detection). Two
    // Re-Kernel variants use different protocols, so send both (best-effort):
    //   Integrate build: payload is the text "#proc_remove" (memcmp'd against nlmsg_len bytes).
    //   LKM build: payload is struct rekernel_cmd { int type = REKERNEL_CMD_REMOVE_PROC }.
    // The socket stays bound and keeps receiving either way. Failure just leaves the node.
    private fun removeProcNode(descriptor: FileDescriptor) {
        // Integrate: nlmsg_len must equal the text length exactly (no trailing NUL).
        runCatching {
            val text = "#proc_remove".toByteArray(StandardCharsets.UTF_8)
            sendNetlink(descriptor, text)
        }.onFailure { xposed.log(Log.WARN, TAG, "REMOVE_PROC (text) failed", it) }
        // LKM: 4-byte struct rekernel_cmd { int type }.
        runCatching {
            val cmd = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
                .putInt(REKERNEL_CMD_REMOVE_PROC).array()
            sendNetlink(descriptor, cmd)
        }.onFailure { xposed.log(Log.WARN, TAG, "REMOVE_PROC (cmd) failed", it) }
        xposed.log(Log.INFO, TAG, "Sent Re-Kernel proc-remove (both variants)")
    }

    // Wrap payload in a standard nlmsghdr (len = HDRLEN + payload) and send to the kernel (port 0).
    private fun sendNetlink(descriptor: FileDescriptor, payload: ByteArray) {
        val total = NLMSG_HDRLEN + payload.size
        val msg = ByteBuffer.allocate(total).order(ByteOrder.nativeOrder())
        msg.putInt(total)   // nlmsg_len
        msg.putShort(0)     // nlmsg_type
        msg.putShort(0)     // nlmsg_flags
        msg.putInt(0)       // nlmsg_seq
        msg.putInt(0)       // nlmsg_pid (0 = kernel)
        msg.put(payload)
        Os.sendto(descriptor, msg.array(), 0, total, 0, newNetlinkSocketAddress(0))
    }

    // android.system.NetlinkSocketAddress(nlPortId, nlGroupsMask) is @hide -> reflect.
    private fun newNetlinkSocketAddress(port: Int): SocketAddress {
        val clazz = Class.forName("android.system.NetlinkSocketAddress")
        return try {
            clazz.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(port, 0) as SocketAddress
        } catch (ignored: NoSuchMethodException) {
            clazz.getConstructor(Int::class.javaPrimitiveType)
                .newInstance(port) as SocketAddress
        }
    }

    private companion object {
        const val TAG = "Nemuri"
        const val PROC_REKERNEL = "/proc/rekernel"
        const val DEFAULT_UNIT = 22
        const val NETLINK_PORT = 100
        const val MAX_ERRORS = 8
        const val NLMSG_HDRLEN = 16 // aligned sizeof(struct nlmsghdr)
        const val REKERNEL_CMD_REMOVE_PROC = 1
    }
}
