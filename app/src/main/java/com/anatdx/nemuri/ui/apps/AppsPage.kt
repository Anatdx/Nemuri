/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - App list screen and per-app policy detail page.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anatdx.nemuri.R
import com.anatdx.nemuri.data.apps.AppPolicy
import com.anatdx.nemuri.data.apps.FreezeStrategy
import com.anatdx.nemuri.data.apps.InstalledAppInfo
import com.anatdx.nemuri.ui.common.NemuriMotion
import com.anatdx.nemuri.ui.components.PressableSurface
import com.anatdx.nemuri.ui.navigation.AppFilter
import com.anatdx.nemuri.viewmodel.AppsViewModel

@Composable
fun AppsPage(
    innerPadding: PaddingValues,
    appFilter: AppFilter,
    searchQuery: String,
    appsViewModel: AppsViewModel,
    onDetailActiveChange: (Boolean) -> Unit,
) {
    val uiState by appsViewModel.uiState.collectAsState()
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedApp = selectedPackage?.let { packageName ->
        uiState.apps.firstOrNull { it.packageName == packageName }
    }

    LaunchedEffect(selectedApp) {
        onDetailActiveChange(selectedApp != null)
    }

    val visibleApps = remember(uiState.apps, appFilter, searchQuery) {
        val normalizedQuery = searchQuery.trim().lowercase()
        uiState.apps
            .filter { app -> appFilter.matches(app) }
            .filter { app ->
                normalizedQuery.isEmpty() ||
                    app.label.lowercase().contains(normalizedQuery) ||
                    app.packageName.lowercase().contains(normalizedQuery)
            }
    }

    BackHandler(enabled = selectedApp != null) {
        selectedPackage = null
    }

    AnimatedContent(
        targetState = selectedApp,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(
                    animationSpec = tween(NemuriMotion.Medium),
                    initialOffsetX = { it }
                ) + fadeIn(animationSpec = tween(NemuriMotion.Medium))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = tween(NemuriMotion.Medium),
                        targetOffsetX = { -it / 4 }
                    ) + fadeOut(animationSpec = tween(NemuriMotion.Short)))
            } else {
                (slideInHorizontally(
                    animationSpec = tween(NemuriMotion.Medium),
                    initialOffsetX = { -it / 4 }
                ) + fadeIn(animationSpec = tween(NemuriMotion.Medium))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = tween(NemuriMotion.Medium),
                        targetOffsetX = { it }
                    ) + fadeOut(animationSpec = tween(NemuriMotion.Short)))
            }
        },
        label = "appListDetail"
    ) { app ->
        if (app != null) {
            AppPolicyPage(
                innerPadding = innerPadding,
                app = app,
                policy = uiState.policies[app.packageName] ?: AppPolicy(app.packageName),
                configPath = appsViewModel.configPath,
                onPolicyChange = appsViewModel::savePolicy
            )
        } else {
            PullToRefreshBox(
                isRefreshing = uiState.loading,
                onRefresh = appsViewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (uiState.loading && visibleApps.isEmpty()) {
                        item {
                            LoadingCard()
                        }
                    } else if (visibleApps.isEmpty()) {
                        item {
                            EmptyAppsCard()
                        }
                    } else {
                        items(
                            items = visibleApps,
                            key = { it.packageName },
                            contentType = { "appRow" }
                        ) { listedApp ->
                            AppRow(
                                app = listedApp,
                                policy = uiState.policies[listedApp.packageName],
                                onClick = { selectedPackage = listedApp.packageName }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPolicyPage(
    innerPadding: PaddingValues,
    app: InstalledAppInfo,
    policy: AppPolicy,
    configPath: String,
    onPolicyChange: (AppPolicy) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            AppPolicyHeader(app = app, policy = policy)
        }
        item {
            PolicySwitchRow(
                title = stringResource(R.string.policy_enable_freeze),
                description = stringResource(R.string.policy_enable_freeze_description),
                checked = policy.enabled,
                onCheckedChange = { onPolicyChange(policy.copy(enabled = it)) }
            )
        }
        item {
            StrategySelector(
                selected = policy.strategy,
                onSelected = { onPolicyChange(policy.copy(strategy = it)) }
            )
        }
        item {
            PolicySwitchRow(
                title = stringResource(R.string.policy_relax_charging),
                description = stringResource(R.string.policy_relax_charging_description),
                checked = policy.allowWhileCharging,
                onCheckedChange = { onPolicyChange(policy.copy(allowWhileCharging = it)) }
            )
        }
        item {
            PolicySwitchRow(
                title = stringResource(R.string.policy_block_wakeups),
                description = stringResource(R.string.policy_block_wakeups_description),
                checked = policy.blockWakeups,
                onCheckedChange = { onPolicyChange(policy.copy(blockWakeups = it)) }
            )
        }
        item {
            ConfigPathCard(configPath = configPath)
        }
    }
}

@Composable
private fun LoadingCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(NemuriMotion.Medium)),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.apps_loading_list),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun EmptyAppsCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(NemuriMotion.Medium)),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = stringResource(R.string.apps_empty),
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppRow(
    app: InstalledAppInfo,
    policy: AppPolicy?,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (policy?.enabled == true) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(NemuriMotion.Medium),
        label = "appRowContainer"
    )
    val leadingTint by animateColorAsState(
        targetValue = if (app.system) {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(NemuriMotion.Medium),
        label = "appRowIcon"
    )

    PressableSurface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        onClick = onClick
    ) {
        ListItem(
            headlineContent = { Text(app.label) },
            supportingContent = { Text(app.packageName) },
            leadingContent = {
                AppIcon(
                    app = app,
                    fallbackTint = leadingTint
                )
            },
            trailingContent = if (policy?.enabled == true) {
                {
                    Text(
                        text = stringResource(policy.strategy.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                null
            },
            colors = androidx.compose.material3.ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun AppIcon(
    app: InstalledAppInfo,
    fallbackTint: Color,
) {
    val bitmap = app.icon
    if (bitmap != null) {
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = Modifier.size(42.dp)
        )
    } else {
        Icon(
            imageVector = Icons.Rounded.Apps,
            contentDescription = null,
            tint = fallbackTint,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun AppPolicyHeader(
    app: InstalledAppInfo,
    policy: AppPolicy,
) {
    val containerColor by animateColorAsState(
        targetValue = if (policy.enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(NemuriMotion.Medium),
        label = "policyHeaderContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (policy.enabled) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(NemuriMotion.Medium),
        label = "policyHeaderContent"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(NemuriMotion.Medium)),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (policy.enabled) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(stringResource(if (app.system) R.string.app_type_system_short else R.string.app_type_user_short))
                    }
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(stringResource(if (policy.enabled) R.string.policy_enabled_short else R.string.policy_disabled_short))
                    }
                )
            }
        }
    }
}

@Composable
private fun PolicySwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    PressableSurface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = { onCheckedChange(!checked) }
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(description) },
            trailingContent = {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            },
            colors = androidx.compose.material3.ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun StrategySelector(
    selected: FreezeStrategy,
    onSelected: (FreezeStrategy) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(NemuriMotion.Medium)),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.freeze_strategy_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FreezeStrategy.entries.forEach { strategy ->
                    FilterChip(
                        selected = selected == strategy,
                        onClick = { onSelected(strategy) },
                        label = { Text(stringResource(strategy.labelRes)) }
                    )
                }
            }
            AnimatedContent(
                targetState = stringResource(selected.descriptionRes),
                transitionSpec = {
                    fadeIn(animationSpec = tween(NemuriMotion.Short)) togetherWith
                        fadeOut(animationSpec = tween(NemuriMotion.Short))
                },
                label = "strategyDescription"
            ) { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ConfigPathCard(configPath: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(NemuriMotion.Medium)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.config_file_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = configPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
