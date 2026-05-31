/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Settings screen (placeholder controls for the future freeze engine).
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anatdx.nemuri.R
import com.anatdx.nemuri.ui.common.NemuriMotion

@Composable
fun SettingsPage(innerPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionHeader(
                title = stringResource(R.string.settings_title),
                description = stringResource(R.string.settings_description)
            )
        }
        items(SettingPlaceholder.entries) { item ->
            SettingRow(item = item)
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    description: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingRow(item: SettingPlaceholder) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(NemuriMotion.Medium)),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        ListItem(
            headlineContent = { Text(stringResource(item.titleRes)) },
            supportingContent = { Text(stringResource(item.descriptionRes)) },
            leadingContent = {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.tint,
                )
            },
            trailingContent = {
                Switch(
                    checked = item.checked,
                    onCheckedChange = null,
                    enabled = false,
                )
            },
            colors = androidx.compose.material3.ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )
    }
}

private enum class SettingPlaceholder(
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val checked: Boolean,
) {
    AutoFreeze(
        titleRes = R.string.setting_auto_freeze,
        descriptionRes = R.string.setting_auto_freeze_description,
        icon = Icons.Rounded.Policy,
        tint = Color(0xFF4E5F9B),
        checked = false,
    ),
    WakeGuard(
        titleRes = R.string.setting_wake_guard,
        descriptionRes = R.string.setting_wake_guard_description,
        icon = Icons.Rounded.VerifiedUser,
        tint = Color(0xFF8A6500),
        checked = false,
    ),
    VerboseLogs(
        titleRes = R.string.setting_debug_logs,
        descriptionRes = R.string.setting_debug_logs_description,
        icon = Icons.Rounded.Terminal,
        tint = Color(0xFF2D6A4F),
        checked = true,
    ),
}
