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
    primary = CloudBlue600,
    onPrimary = Color.White,
    primaryContainer = CloudBlue100,
    onPrimaryContainer = Navy900,
    secondary = Teal600,
    onSecondary = Color.White,
    secondaryContainer = Teal100,
    background = Mist50,
    onBackground = Navy950,
    surface = Color.White,
    onSurface = Navy950,
    surfaceVariant = Color(0xFFEDF3FA),
    outlineVariant = Color(0xFFD8E2ED),
    error = ErrorLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = CloudBlue300,
    onPrimary = Navy950,
    primaryContainer = CloudBlue700,
    onPrimaryContainer = CloudBlue100,
    secondary = Teal300,
    onSecondary = Navy900,
    secondaryContainer = Teal800,
    background = Navy950,
    onBackground = Mist100,
    surface = Navy900,
    onSurface = Mist100,
    surfaceVariant = Navy800,
    outlineVariant = Slate700,
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
