/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Top-level scaffold with bottom-navigation between pages.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anatdx.nemuri.R
import com.anatdx.nemuri.data.apps.InstalledAppInfo
import com.anatdx.nemuri.data.runtime.FrameworkRuntimeClient
import com.anatdx.nemuri.data.settings.SettingsStore
import com.anatdx.nemuri.ui.apps.AppsPage
import com.anatdx.nemuri.ui.common.NemuriMotion
import com.anatdx.nemuri.ui.home.HomePage
import com.anatdx.nemuri.ui.settings.SettingsPage
import com.anatdx.nemuri.viewmodel.AppsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NemuriApp(appsViewModel: AppsViewModel) {
    var selectedPage by rememberSaveable { mutableStateOf(NemuriPage.Home) }
    var appFilter by rememberSaveable { mutableStateOf(AppFilter.User) }
    var appSearchActive by rememberSaveable { mutableStateOf(false) }
    var appSearchQuery by rememberSaveable { mutableStateOf("") }
    var appsDetailActive by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    LaunchedEffect(Unit) {
        // Sync the saved verbose-logging preference to the system_server module on launch.
        FrameworkRuntimeClient.setLogEnabled(context, settingsStore.verboseLogging)
    }
    val selectedPageTitle = stringResource(selectedPage.titleRes)
    val appSearchVisible = selectedPage == NemuriPage.Apps && !appsDetailActive && appSearchActive

    LaunchedEffect(selectedPage) {
        if (selectedPage != NemuriPage.Apps) {
            appsDetailActive = false
            appSearchActive = false
            appSearchQuery = ""
        }
    }

    LaunchedEffect(appsDetailActive) {
        if (appsDetailActive) {
            appSearchActive = false
            appSearchQuery = ""
        }
    }

    BackHandler(enabled = appSearchVisible) {
        appSearchActive = false
        appSearchQuery = ""
    }

    BackHandler(enabled = selectedPage != NemuriPage.Home && !appSearchVisible) {
        selectedPage = NemuriPage.Home
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedContent(
                        targetState = appSearchVisible,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(NemuriMotion.Short)) togetherWith
                                fadeOut(animationSpec = tween(NemuriMotion.Short))
                        },
                        label = "topBarTitle"
                    ) { searching ->
                        if (searching) {
                            AppSearchTopBar(
                                query = appSearchQuery,
                                onQueryChange = { appSearchQuery = it }
                            )
                        } else {
                            Text(
                                text = selectedPageTitle,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    if (selectedPage == NemuriPage.Apps && !appsDetailActive) {
                        if (appSearchActive) {
                            IconButton(
                                onClick = {
                                    appSearchActive = false
                                    appSearchQuery = ""
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.apps_search_close)
                                )
                            }
                        } else {
                            IconButton(onClick = { appSearchActive = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = stringResource(R.string.apps_search_action)
                                )
                            }
                            AppFilterMenu(
                                selectedFilter = appFilter,
                                onFilterChange = { appFilter = it }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NemuriPage.entries.forEach { page ->
                    val label = stringResource(page.labelRes)
                    NavigationBarItem(
                        selected = selectedPage == page,
                        onClick = {
                            selectedPage = page
                            if (page != NemuriPage.Apps) {
                                appSearchActive = false
                                appSearchQuery = ""
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = page.icon,
                                contentDescription = label
                            )
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = selectedPage,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val enterOffset = if (forward) { width: Int -> width / 3 } else { width: Int -> -width / 3 }
                val exitOffset = if (forward) { width: Int -> -width / 5 } else { width: Int -> width / 5 }

                (slideInHorizontally(
                    animationSpec = tween(NemuriMotion.Medium),
                    initialOffsetX = enterOffset
                ) + fadeIn(animationSpec = tween(NemuriMotion.Medium))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = tween(NemuriMotion.Medium),
                        targetOffsetX = exitOffset
                    ) + fadeOut(animationSpec = tween(NemuriMotion.Short)))
            },
            label = "rootPage"
        ) { page ->
            when (page) {
                NemuriPage.Home -> HomePage(innerPadding, appsViewModel)
                NemuriPage.Apps -> AppsPage(
                    innerPadding = innerPadding,
                    appFilter = appFilter,
                    searchQuery = appSearchQuery,
                    appsViewModel = appsViewModel,
                    onDetailActiveChange = { appsDetailActive = it }
                )
                NemuriPage.Settings -> SettingsPage(innerPadding, appsViewModel, settingsStore)
            }
        }
    }
}

@Composable
private fun AppFilterMenu(
    selectedFilter: AppFilter,
    onFilterChange: (AppFilter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Rounded.MoreVert,
            contentDescription = stringResource(R.string.app_filter_menu)
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        AppFilter.entries.forEach { filter ->
            DropdownMenuItem(
                text = { Text(stringResource(filter.labelRes)) },
                onClick = {
                    onFilterChange(filter)
                    expanded = false
                }
            )
        }
    }
}

@Composable
private fun AppSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = textColor,
        shape = RoundedCornerShape(24.dp)
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = textColor),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.apps_search_placeholder),
                            style = MaterialTheme.typography.titleMedium,
                            color = placeholderColor
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

enum class AppFilter(
    val labelRes: Int,
) {
    User(R.string.app_filter_user),
    System(R.string.app_filter_system);

    fun matches(app: InstalledAppInfo): Boolean = when (this) {
        User -> !app.system
        System -> app.system
    }
}

private enum class NemuriPage(
    val titleRes: Int,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Home(R.string.app_name, R.string.nav_home, Icons.Rounded.Home),
    Apps(R.string.apps_title, R.string.nav_apps, Icons.Rounded.Apps),
    Settings(R.string.settings_title, R.string.nav_settings, Icons.Rounded.Settings),
}
