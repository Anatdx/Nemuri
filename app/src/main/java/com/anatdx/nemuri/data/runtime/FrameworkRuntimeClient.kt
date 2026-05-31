/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - UI-side client that talks to the system_server runtime bridge over Binder.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.data.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.anatdx.nemuri.xposed.bridge.NemuriBridgeProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BackgroundProcessDetail(
    val processName: String,
    val pid: Int,
    val procState: Int,
)

data class BackgroundAppSnapshot(
    val packageName: String,
    val uid: Int,
    val aggregateProcState: Int,
    val exemptionFlags: Int,
    val frozen: Boolean,
    val processes: List<BackgroundProcessDetail>,
)

sealed interface BackgroundProcessResult {
    data class Success(val apps: List<BackgroundAppSnapshot>) : BackgroundProcessResult
    data class Failure(val message: String) : BackgroundProcessResult
}

object FrameworkRuntimeClient {
    suspend fun getBackgroundProcesses(context: Context): BackgroundProcessResult = withContext(Dispatchers.IO) {
        runCatching {
            val binder = requestRuntimeBinder(context.applicationContext)
                ?: return@withContext BackgroundProcessResult.Failure("Nemuri runtime Binder is unavailable")
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(NemuriBridgeProtocol.DESCRIPTOR)
                val handled = binder.transact(
                    NemuriBridgeProtocol.TRANSACTION_GET_BACKGROUND_PROCESSES,
                    data,
                    reply,
                    0
                )
                if (!handled) {
                    return@withContext BackgroundProcessResult.Failure("Nemuri runtime Binder rejected the request")
                }
                reply.readException()
                val appCount = reply.readInt().coerceAtLeast(0)
                val apps = buildList(appCount) {
                    repeat(appCount) {
                        val packageName = reply.readString().orEmpty()
                        val uid = reply.readInt()
                        val aggregateProcState = reply.readInt()
                        val exemptionFlags = reply.readInt()
                        val frozen = reply.readInt() != 0
                        val processCount = reply.readInt().coerceAtLeast(0)
                        val processes = buildList(processCount) {
                            repeat(processCount) {
                                add(
                                    BackgroundProcessDetail(
                                        processName = reply.readString().orEmpty(),
                                        pid = reply.readInt(),
                                        procState = reply.readInt(),
                                    )
                                )
                            }
                        }
                        add(
                            BackgroundAppSnapshot(
                                packageName = packageName,
                                uid = uid,
                                aggregateProcState = aggregateProcState,
                                exemptionFlags = exemptionFlags,
                                frozen = frozen,
                                processes = processes,
                            )
                        )
                    }
                }
                BackgroundProcessResult.Success(apps)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.getOrElse { throwable ->
            BackgroundProcessResult.Failure(throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    suspend fun setFrozen(context: Context, uid: Int, frozen: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val binder = requestRuntimeBinder(context.applicationContext) ?: return@withContext false
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(NemuriBridgeProtocol.DESCRIPTOR)
                data.writeInt(uid)
                data.writeInt(if (frozen) 1 else 0)
                val handled = binder.transact(NemuriBridgeProtocol.TRANSACTION_SET_FROZEN, data, reply, 0)
                if (!handled) {
                    return@withContext false
                }
                reply.readException()
                reply.readInt() == NemuriBridgeProtocol.REPLY_SUCCESS
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.getOrDefault(false)
    }

    private fun requestRuntimeBinder(context: Context): IBinder? {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                null,
                IntentFilter(NemuriBridgeProtocol.ACTION_BINDER),
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(null, IntentFilter(NemuriBridgeProtocol.ACTION_BINDER))
        }
        return intent?.extras?.getBinder(NemuriBridgeProtocol.EXTRA_BRIDGE_BINDER)
    }
}
