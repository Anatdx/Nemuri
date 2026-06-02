/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Shared constants for the manager <-> system_server Binder protocol.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

object NemuriBridgeProtocol {
    const val DESCRIPTOR = "com.anatdx.nemuri.IRuntimeBridge"
    const val MANAGER_PACKAGE_NAME = "com.anatdx.nemuri"
    const val ACTION_BINDER = "com.anatdx.nemuri.action.RUNTIME_BINDER"
    const val EXTRA_BRIDGE_BINDER = "com.anatdx.nemuri.extra.BRIDGE_BINDER"

    const val TRANSACTION_GET_BACKGROUND_PROCESSES = 1
    const val TRANSACTION_SET_FROZEN = 2
    const val TRANSACTION_SET_LOG_ENABLED = 3
    const val TRANSACTION_SET_POLICY = 4
    const val REPLY_SUCCESS = 0
    const val REPLY_FAILURE = -1

    // Observation layer: per-app exemption flags (bitmask). Empty mask => would be frozen.
    // Detection only; nothing here changes freeze state.
    const val EXEMPT_NONE = 0
    const val EXEMPT_SELF = 1
    const val EXEMPT_SYSTEM = 1 shl 1
    const val EXEMPT_XPOSED_MODULE = 1 shl 2
    const val EXEMPT_INPUT_METHOD = 1 shl 3
    const val EXEMPT_LAUNCHER = 1 shl 4
    const val EXEMPT_ACCESSIBILITY = 1 shl 5
    const val EXEMPT_AUDIO = 1 shl 6
    const val EXEMPT_MICROPHONE = 1 shl 7
    const val EXEMPT_VPN = 1 shl 8
    const val EXEMPT_LOCATION = 1 shl 9
}
