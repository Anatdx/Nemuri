package com.anatdx.nemuri.ui.home

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anatdx.nemuri.BuildConfig
import com.anatdx.nemuri.R
import com.anatdx.nemuri.data.apps.InstalledAppInfo
import com.anatdx.nemuri.data.runtime.BackgroundAppSnapshot
import com.anatdx.nemuri.data.runtime.BackgroundProcessDetail
import com.anatdx.nemuri.data.runtime.BackgroundProcessResult
import com.anatdx.nemuri.data.runtime.FrameworkRuntimeClient
import com.anatdx.nemuri.data.runtime.RuntimeStats
import com.anatdx.nemuri.ui.common.NemuriMotion
import com.anatdx.nemuri.ui.components.PressableSurface
import com.anatdx.nemuri.viewmodel.AppsViewModel
import com.anatdx.nemuri.xposed.ModuleStatus
import com.anatdx.nemuri.xposed.XposedServiceStatus
import kotlinx.coroutines.launch

@Composable
fun HomePage(
    innerPadding: PaddingValues,
    appsViewModel: AppsViewModel,
) {
    val context = LocalContext.current
    val status = XposedServiceStatus.state.value
    val appsUiState by appsViewModel.uiState.collectAsState()
    val appInfoByPackage = remember(appsUiState.apps) {
        appsUiState.apps.associateBy { it.packageName }
    }
    var backgroundResult by remember { mutableStateOf<BackgroundProcessResult?>(null) }
    var loadingBackground by remember { mutableStateOf(false) }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val backgroundApps = when (val result = backgroundResult) {
        is BackgroundProcessResult.Success -> result.apps
        else -> emptyList()
    }
    val runtimeStats = RuntimeStats(
        frozenBackgroundApps = 0,
        totalBackgroundApps = backgroundApps.size
    )
    val deviceName = remember {
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { Build.DEVICE }
    }

    suspend fun refreshBackgroundProcesses() {
        loadingBackground = true
        backgroundResult = FrameworkRuntimeClient.getBackgroundProcesses(context)
        loadingBackground = false
    }

    LaunchedEffect(status.active) {
        if (status.active) {
            refreshBackgroundProcesses()
        }
    }

    // Drop the open detail if its app left the latest snapshot.
    LaunchedEffect(backgroundApps) {
        if (selectedPackage != null && backgroundApps.none { it.packageName == selectedPackage }) {
            selectedPackage = null
        }
    }

    BackHandler(enabled = selectedPackage != null) {
        selectedPackage = null
    }

    AnimatedContent(
        targetState = selectedPackage,
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
        label = "homeDetail"
    ) { pkg ->
        val detailApp = pkg?.let { p -> backgroundApps.firstOrNull { it.packageName == p } }
        if (detailApp != null) {
            BackgroundAppDetailPage(
                innerPadding = innerPadding,
                app = detailApp,
                info = appInfoByPackage[detailApp.packageName]
            )
        } else {
            HomeContent(
                innerPadding = innerPadding,
                status = status,
                runtimeStats = runtimeStats,
                deviceName = deviceName,
                backgroundResult = backgroundResult,
                backgroundApps = backgroundApps,
                appInfoByPackage = appInfoByPackage,
                loading = loadingBackground,
                onRefresh = { scope.launch { refreshBackgroundProcesses() } },
                onAppClick = { selectedPackage = it }
            )
        }
    }
}

@Composable
private fun HomeContent(
    innerPadding: PaddingValues,
    status: ModuleStatus,
    runtimeStats: RuntimeStats,
    deviceName: String,
    backgroundResult: BackgroundProcessResult?,
    backgroundApps: List<BackgroundAppSnapshot>,
    appInfoByPackage: Map<String, InstalledAppInfo>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onAppClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            WorkingStatusCard(status = status)
        }
        item {
            SystemInfoCard(
                status = status,
                runtimeStats = runtimeStats,
                deviceName = deviceName
            )
        }
        item {
            BackgroundProcessCard(
                result = backgroundResult,
                apps = backgroundApps,
                appInfoByPackage = appInfoByPackage,
                loading = loading,
                onRefresh = onRefresh,
                onAppClick = onAppClick
            )
        }
    }
}

@Composable
private fun WorkingStatusCard(status: ModuleStatus) {
    val targetContainerColor = if (status.active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val targetContentColor = if (status.active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(NemuriMotion.Medium),
        label = "moduleStatusContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = tween(NemuriMotion.Medium),
        label = "moduleStatusContent"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(NemuriMotion.Medium)),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (status.active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.width(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(
                        if (status.active) {
                            R.string.module_status_working
                        } else {
                            R.string.module_status_stopped
                        }
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (status.active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.nemuri_version_format, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SystemInfoCard(
    status: ModuleStatus,
    runtimeStats: RuntimeStats,
    deviceName: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(NemuriMotion.Medium)),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            SystemInfoItem(
                icon = Icons.Rounded.Widgets,
                title = stringResource(R.string.framework_version_title),
                value = status.frameworkLabel
            )
            SystemInfoItem(
                icon = Icons.Rounded.Extension,
                title = stringResource(R.string.framework_api_title),
                value = status.apiLabel
            )
            SystemInfoItem(
                icon = Icons.Rounded.Android,
                title = stringResource(R.string.android_version_title),
                value = Build.VERSION.RELEASE
            )
            SystemInfoItem(
                icon = Icons.Rounded.Smartphone,
                title = stringResource(R.string.device_title),
                value = deviceName
            )
            SystemInfoItem(
                icon = Icons.Rounded.Apps,
                title = stringResource(R.string.background_freeze_title),
                value = stringResource(
                    R.string.background_freeze_count_format,
                    runtimeStats.frozenBackgroundApps,
                    runtimeStats.totalBackgroundApps
                )
            )
        }
    }
}

@Composable
private fun SystemInfoItem(
    icon: ImageVector,
    title: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(34.dp)
        )
        Spacer(modifier = Modifier.width(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun BackgroundProcessCard(
    result: BackgroundProcessResult?,
    apps: List<BackgroundAppSnapshot>,
    appInfoByPackage: Map<String, InstalledAppInfo>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onAppClick: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(NemuriMotion.Medium)),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = stringResource(R.string.background_process_snapshot_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.background_process_snapshot_count, apps.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = onRefresh, enabled = !loading) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.background_process_snapshot_refresh),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            when (result) {
                is BackgroundProcessResult.Success -> {
                    if (apps.isEmpty()) {
                        Text(
                            text = stringResource(R.string.background_process_snapshot_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            apps.take(8).forEach { app ->
                                BackgroundAppRow(
                                    app = app,
                                    info = appInfoByPackage[app.packageName],
                                    onClick = { onAppClick(app.packageName) }
                                )
                            }
                        }
                    }
                }

                is BackgroundProcessResult.Failure -> {
                    Text(
                        text = stringResource(
                            R.string.background_process_snapshot_error,
                            result.message
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                null -> {
                    Text(
                        text = stringResource(R.string.background_process_snapshot_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundAppRow(
    app: BackgroundAppSnapshot,
    info: InstalledAppInfo?,
    onClick: () -> Unit,
) {
    PressableSurface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppBitmapIcon(bitmap = info?.icon, modifier = Modifier.size(38.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = info?.label ?: app.packageName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.background_process_snapshot_detail,
                        app.uid,
                        app.processes.size,
                        app.aggregateProcState
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BackgroundAppDetailPage(
    innerPadding: PaddingValues,
    app: BackgroundAppSnapshot,
    info: InstalledAppInfo?,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            BackgroundAppDetailHeader(app = app, info = info)
        }
        item {
            Text(
                text = stringResource(R.string.background_process_list_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
        if (app.processes.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.background_process_snapshot_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(
                items = app.processes,
                key = { "${it.processName}#${it.pid}" }
            ) { process ->
                ProcessRow(process = process)
            }
        }
    }
}

@Composable
private fun BackgroundAppDetailHeader(
    app: BackgroundAppSnapshot,
    info: InstalledAppInfo?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppBitmapIcon(bitmap = info?.icon, modifier = Modifier.size(52.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = info?.label ?: app.packageName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.background_process_snapshot_detail,
                        app.uid,
                        app.processes.size,
                        app.aggregateProcState
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProcessRow(process: BackgroundProcessDetail) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = process.processName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.background_process_item_detail,
                    process.pid,
                    process.procState
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppBitmapIcon(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    if (bitmap != null) {
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = modifier
        )
    } else {
        Icon(
            imageVector = Icons.Rounded.Apps,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier
        )
    }
}
