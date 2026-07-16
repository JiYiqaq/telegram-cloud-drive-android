/*
 * Initial implementation created with OpenAI Codex
 * based on requirements provided by the project maintainer.
 */

package com.teledrive.lite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Teal600,
    onPrimary = Color.White,
    primaryContainer = Teal100,
    onPrimaryContainer = Navy900,
    secondary = Slate700,
    onSecondary = Color.White,
    background = Mist50,
    onBackground = Navy950,
    surface = Color.White,
    onSurface = Navy950,
    error = ErrorLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = Teal300,
    onPrimary = Navy950,
    primaryContainer = Teal800,
    onPrimaryContainer = Teal100,
    secondary = Slate200,
    onSecondary = Navy900,
    background = Navy950,
    onBackground = Mist100,
    surface = Navy900,
    onSurface = Mist100,
    error = ErrorDark,
)

@Composable
fun TeleDriveTheme(
    darkThemeOverride: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = darkThemeOverride ?: isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = TeleDriveTypography,
        content = content,
    )
}
