/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Runtime stats model shown on the home screen.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.data.runtime

data class RuntimeStats(
    val frozenBackgroundApps: Int = 0,
    val totalBackgroundApps: Int = 0,
)

object RuntimeStatsRepository {
    fun load(): RuntimeStats = RuntimeStats()
}
