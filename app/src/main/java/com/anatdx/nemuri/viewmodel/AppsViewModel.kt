/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - View model that preloads the installed-app list and exposes app policies.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.viewmodel

import android.app.Application
import com.anatdx.nemuri.data.apps.AppPolicy
import com.anatdx.nemuri.data.apps.AppPolicyStore
import com.anatdx.nemuri.data.apps.AppRepository
import com.anatdx.nemuri.data.apps.InstalledAppInfo
import com.anatdx.nemuri.data.runtime.FrameworkRuntimeClient
import com.anatdx.nemuri.data.settings.SettingsStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppsUiState(
    val apps: List<InstalledAppInfo> = emptyList(),
    val policies: Map<String, AppPolicy> = emptyMap(),
    val loading: Boolean = true,
)

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val policyStore = AppPolicyStore(appContext)
    private val settingsStore = SettingsStore(appContext)
    private val _uiState = MutableStateFlow(AppsUiState())
    private var preloadJob: Job? = null

    val uiState: StateFlow<AppsUiState> = _uiState.asStateFlow()
    val configPath: String = policyStore.configPath

    init {
        refresh()
    }

    fun refresh() {
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val loaded = withContext(Dispatchers.IO) {
                val apps = AppRepository.loadInstalledApps(appContext)
                val policies = policyStore.loadAll()
                apps to policies
            }
            _uiState.value = AppsUiState(
                apps = loaded.first,
                policies = loaded.second,
                loading = false,
            )
        }
    }

    fun savePolicy(policy: AppPolicy) {
        _uiState.update { state ->
            state.copy(policies = state.policies + (policy.packageName to policy))
        }
        viewModelScope.launch(Dispatchers.IO) {
            policyStore.save(policy)
            pushPolicy()
        }
    }

    // Set the master switch and push the full policy. Single entry point for the settings toggle
    // so the whitelist is never sent empty (which would wipe the persisted/seeded one).
    fun setAutoFreeze(enabled: Boolean) {
        settingsStore.autoFreezeEnabled = enabled
        viewModelScope.launch { pushPolicy() }
    }

    // Push current saved prefs + loaded whitelist to system_server. Waits for policies to finish
    // loading first, so the whitelist is complete.
    suspend fun pushPolicy() {
        _uiState.first { !it.loading }
        FrameworkRuntimeClient.setPolicy(
            context = appContext,
            enabled = settingsStore.autoFreezeEnabled,
            whitelist = whitelistedPackages(),
            delayMs = settingsStore.freezeDelaySeconds * 1000L,
        )
    }

    // Opt-out model: a per-app policy with enabled=true means "never freeze" (whitelist).
    fun whitelistedPackages(): List<String> =
        _uiState.value.policies.values.filter { it.enabled }.map { it.packageName }

    suspend fun exportConfig(): String = withContext(Dispatchers.IO) { policyStore.readRawConfig() }

    suspend fun importConfig(json: String): Boolean {
        val ok = withContext(Dispatchers.IO) { policyStore.writeRawConfig(json) }
        if (ok) {
            refresh()
            pushPolicy()
        }
        return ok
    }
}
