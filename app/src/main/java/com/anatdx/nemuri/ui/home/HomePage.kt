package com.anatdx.nemuri.ui.home

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anatdx.nemuri.BuildConfig
import com.anatdx.nemuri.R
import com.anatdx.nemuri.data.runtime.RuntimeStats
import com.anatdx.nemuri.data.runtime.RuntimeStatsRepository
import com.anatdx.nemuri.ui.common.NemuriMotion
import com.anatdx.nemuri.xposed.ModuleStatus
import com.anatdx.nemuri.xposed.XposedServiceStatus

@Composable
fun HomePage(innerPadding: PaddingValues) {
    val status = XposedServiceStatus.state.value
    val runtimeStats = remember { RuntimeStatsRepository.load() }
    val deviceName = remember {
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { Build.DEVICE }
    }

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
