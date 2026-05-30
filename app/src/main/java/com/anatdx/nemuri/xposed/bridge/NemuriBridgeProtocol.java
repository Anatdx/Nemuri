package com.anatdx.nemuri.xposed.bridge;

public final class NemuriBridgeProtocol {
    public static final String DESCRIPTOR = "com.anatdx.nemuri.IRuntimeBridge";
    public static final String MANAGER_PACKAGE_NAME = "com.anatdx.nemuri";
    public static final String ACTION_BINDER = "com.anatdx.nemuri.action.RUNTIME_BINDER";
    public static final String EXTRA_BRIDGE_BINDER = "com.anatdx.nemuri.extra.BRIDGE_BINDER";

    public static final int TRANSACTION_GET_BACKGROUND_PROCESSES = 1;
    public static final int REPLY_SUCCESS = 0;
    public static final int REPLY_FAILURE = -1;

    private NemuriBridgeProtocol() {
    }
}
