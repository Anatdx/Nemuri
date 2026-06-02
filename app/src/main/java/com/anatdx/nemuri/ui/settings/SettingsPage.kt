/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Settings screen: config import/export, log toggle, and an about dialog.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.anatdx.nemuri.R
import com.anatdx.nemuri.data.runtime.FrameworkRuntimeClient
import com.anatdx.nemuri.data.settings.SettingsStore
import com.anatdx.nemuri.viewmodel.AppsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GITHUB_URL = "https://github.com/Anatdx/Nemuri"
private const val TELEGRAM_URL = "https://t.me/manosaba"

@Composable
fun SettingsPage(
    innerPadding: PaddingValues,
    appsViewModel: AppsViewModel,
    settingsStore: SettingsStore,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var verboseLogging by remember { mutableStateOf(settingsStore.verboseLogging) }
    var autoFreeze by remember { mutableStateOf(settingsStore.autoFreezeEnabled) }
    var binderUnfreeze by remember { mutableStateOf(settingsStore.binderUnfreezeEnabled) }
    var showAbout by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = appsViewModel.exportConfig()
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray())
                        }
                        true
                    }.getOrDefault(false)
                }
                toast(context, if (ok) R.string.settings_export_done else R.string.settings_io_failed)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                val ok = json != null && appsViewModel.importConfig(json)
                toast(context, if (ok) R.string.settings_import_done else R.string.settings_import_failed)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SettingsGroup(title = stringResource(R.string.settings_group_freeze)) {
                ToggleRow(
                    icon = Icons.Rounded.AcUnit,
                    title = stringResource(R.string.setting_auto_freeze),
                    description = stringResource(R.string.setting_auto_freeze_description),
                    checked = autoFreeze,
                    onCheckedChange = { enabled ->
                        autoFreeze = enabled
                        appsViewModel.setAutoFreeze(enabled)
                    }
                )
                ToggleRow(
                    icon = Icons.Rounded.SyncAlt,
                    title = stringResource(R.string.setting_binder_unfreeze),
                    description = stringResource(R.string.setting_binder_unfreeze_description),
                    checked = binderUnfreeze,
                    onCheckedChange = { enabled ->
                        binderUnfreeze = enabled
                        appsViewModel.setBinderUnfreeze(enabled)
                    }
                )
            }
        }
        item {
            SettingsGroup(title = stringResource(R.string.settings_group_config)) {
                ActionRow(
                    icon = Icons.Rounded.Upload,
                    title = stringResource(R.string.settings_export_title),
                    description = stringResource(R.string.settings_export_desc),
                    onClick = { exportLauncher.launch("nemuri-config.json") }
                )
                ActionRow(
                    icon = Icons.Rounded.Download,
                    title = stringResource(R.string.settings_import_title),
                    description = stringResource(R.string.settings_import_desc),
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }
                )
                InfoRow(
                    icon = Icons.Rounded.Folder,
                    title = stringResource(R.string.config_file_title),
                    value = appsViewModel.configPath
                )
            }
        }
        item {
            SettingsGroup(title = stringResource(R.string.settings_group_logs)) {
                ToggleRow(
                    icon = Icons.Rounded.Terminal,
                    title = stringResource(R.string.setting_debug_logs),
                    description = stringResource(R.string.setting_debug_logs_description),
                    checked = verboseLogging,
                    onCheckedChange = { enabled ->
                        verboseLogging = enabled
                        settingsStore.verboseLogging = enabled
                        scope.launch { FrameworkRuntimeClient.setLogEnabled(context, enabled) }
                    }
                )
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                ActionRow(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.settings_about_title),
                    description = stringResource(R.string.settings_about_desc),
                    onClick = { showAbout = true }
                )
            }
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

private fun toast(context: Context, resId: Int) {
    Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val icon = remember {
        context.packageManager.getApplicationIcon(context.packageName).toBitmap().asImageBitmap()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_close)) }
        },
        icon = {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(64.dp))
        },
        title = {
            Text(text = stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AboutLinkRow(
                    icon = Icons.Rounded.Code,
                    label = stringResource(R.string.about_github),
                    url = GITHUB_URL,
                    onClick = { openUrl(context, GITHUB_URL) }
                )
                AboutLinkRow(
                    icon = Icons.Rounded.Forum,
                    label = stringResource(R.string.about_telegram),
                    url = TELEGRAM_URL,
                    onClick = { openUrl(context, TELEGRAM_URL) }
                )
            }
        }
    )
}

@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    label: String,
    url: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    title: String,
    value: String,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
