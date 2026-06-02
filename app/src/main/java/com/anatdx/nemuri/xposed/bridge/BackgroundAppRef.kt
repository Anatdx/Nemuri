/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Minimal (package, uid) pair the engine's periodic sweep consumes.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.xposed.bridge

class BackgroundAppRef(
    @JvmField val packageName: String,
    @JvmField val uid: Int,
)
