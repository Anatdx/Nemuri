/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - Material 3 theme for the app.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF7FF8E8),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    onSecondary = Color.White,
    tertiary = Color(0xFF456179),
    background = Color(0xFFFAFDFB),
    surface = Color(0xFFFAFDFB),
    surfaceContainer = Color(0xFFECEFED),
    onSurface = Color(0xFF191C1B),
    onSurfaceVariant = Color(0xFF3F4946),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF60DBCC),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF7FF8E8),
    secondary = Color(0xFFB1CCC7),
    onSecondary = Color(0xFF1C3531),
    tertiary = Color(0xFFADC9E6),
    background = Color(0xFF101413),
    surface = Color(0xFF101413),
    surfaceContainer = Color(0xFF1C211F),
    onSurface = Color(0xFFE0E3E1),
    onSurfaceVariant = Color(0xFFBEC9C5),
)

@Composable
fun NemuriTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
