package com.teledrive.lite.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.teledrive.lite.ui.setup.SetupScreen
import com.teledrive.lite.ui.splash.SplashScreen

@Composable
fun TeleDriveNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.Splash,
    ) {
        composable(Routes.Splash) {
            SplashScreen(
                onStartSetup = {
                    navController.navigate(Routes.Setup) {
                        popUpTo(Routes.Splash) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.Setup) {
            SetupScreen()
        }
    }
}
