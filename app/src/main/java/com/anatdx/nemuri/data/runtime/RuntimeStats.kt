package com.anatdx.nemuri.data.runtime

data class RuntimeStats(
    val frozenBackgroundApps: Int = 0,
    val totalBackgroundApps: Int = 0,
)

object RuntimeStatsRepository {
    fun load(): RuntimeStats = RuntimeStats()
}
