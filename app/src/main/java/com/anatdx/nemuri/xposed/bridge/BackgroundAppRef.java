/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Minimal (package, uid) pair the engine's periodic sweep consumes.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge;

public final class BackgroundAppRef {
    public final String packageName;
    public final int uid;

    public BackgroundAppRef(String packageName, int uid) {
        this.packageName = packageName;
        this.uid = uid;
    }
}
