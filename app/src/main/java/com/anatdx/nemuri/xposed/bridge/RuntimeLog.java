/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Shared verbose-logging switch for the system_server side.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge;

// Toggled from the manager UI via TRANSACTION_SET_LOG_ENABLED. High-frequency module logs check
// this; warnings/errors always log. Defaults to true until the UI pushes the saved preference.
public final class RuntimeLog {
    public static volatile boolean verbose = true;

    private RuntimeLog() {
    }
}
