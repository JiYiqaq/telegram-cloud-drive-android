/*
 * Initial implementation created with OpenAI Codex
 * based on requirements provided by the project maintainer.
 */

package com.teledrive.lite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.teledrive.lite.navigation.TeleDriveNavHost
import com.teledrive.lite.ui.theme.TeleDriveTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.teledrive.lite.settings.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = (application as TeleDriveApplication).container
            val themeMode by container.appPreferences.themeMode.collectAsStateWithLifecycle()
            TeleDriveTheme(
                darkThemeOverride = when (themeMode) {
                    ThemeMode.SYSTEM -> null
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TeleDriveNavHost(
                        container = container,
                    )
                }
            }
        }
    }
}
