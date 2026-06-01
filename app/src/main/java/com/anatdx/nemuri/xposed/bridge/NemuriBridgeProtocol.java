/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Shared constants for the manager <-> system_server Binder protocol.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge;

public final class NemuriBridgeProtocol {
    public static final String DESCRIPTOR = "com.anatdx.nemuri.IRuntimeBridge";
    public static final String MANAGER_PACKAGE_NAME = "com.anatdx.nemuri";
    public static final String ACTION_BINDER = "com.anatdx.nemuri.action.RUNTIME_BINDER";
    public static final String EXTRA_BRIDGE_BINDER = "com.anatdx.nemuri.extra.BRIDGE_BINDER";

    public static final int TRANSACTION_GET_BACKGROUND_PROCESSES = 1;
    public static final int TRANSACTION_SET_FROZEN = 2;
    public static final int TRANSACTION_SET_LOG_ENABLED = 3;
    public static final int TRANSACTION_SET_POLICY = 4;
    public static final int REPLY_SUCCESS = 0;
    public static final int REPLY_FAILURE = -1;

    // Observation layer: per-app exemption flags (bitmask). Empty mask => would be frozen.
    // Detection only; nothing here changes freeze state.
    public static final int EXEMPT_NONE = 0;
    public static final int EXEMPT_SELF = 1;
    public static final int EXEMPT_SYSTEM = 1 << 1;
    public static final int EXEMPT_XPOSED_MODULE = 1 << 2;
    public static final int EXEMPT_INPUT_METHOD = 1 << 3;
    public static final int EXEMPT_LAUNCHER = 1 << 4;
    public static final int EXEMPT_ACCESSIBILITY = 1 << 5;
    public static final int EXEMPT_AUDIO = 1 << 6;
    public static final int EXEMPT_MICROPHONE = 1 << 7;
    public static final int EXEMPT_VPN = 1 << 8;
    public static final int EXEMPT_LOCATION = 1 << 9;

    private NemuriBridgeProtocol() {
    }
}
