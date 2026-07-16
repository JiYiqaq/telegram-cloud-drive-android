/*
 * Initial implementation created with OpenAI Codex
 * based on requirements provided by the project maintainer.
 */

package com.teledrive.lite.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teledrive.lite.app.AppContainer
import com.teledrive.lite.ui.home.HomeRoute
import com.teledrive.lite.ui.home.HomeViewModel
import com.teledrive.lite.ui.home.HomeViewModelFactory
import com.teledrive.lite.ui.setup.SetupRoute
import com.teledrive.lite.ui.setup.SetupViewModel
import com.teledrive.lite.ui.setup.SetupViewModelFactory
import com.teledrive.lite.ui.splash.SplashScreen
import com.teledrive.lite.ui.tutorial.TutorialScreen

@Composable
fun TeleDriveNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val startDestination = remember(container) {
        Routes.initialRoute(container.isSetupComplete())
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
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
            val setupViewModel: SetupViewModel = viewModel(
                factory = SetupViewModelFactory(
                    connectionService = container.setupConnectionService,
                    initializationService = container.setupInitializationService,
                ),
            )
            SetupRoute(
                viewModel = setupViewModel,
                onOpenHelp = { navController.navigate(Routes.Tutorial) },
                onSetupComplete = {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Setup) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.Tutorial) {
            TutorialScreen(onBack = navController::popBackStack)
        }
        composable(Routes.Home) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = HomeViewModelFactory(
                    fileRepository = container.fileRepository,
                    transferRepository = container.transferRepository,
                    uploadScheduler = container.uploadScheduler,
                    downloadScheduler = container.downloadScheduler,
                    deletionScheduler = container.deletionScheduler,
                    orphanCleanupScheduler = container.orphanCleanupScheduler,
                ),
            )
            HomeRoute(viewModel = homeViewModel)
        }
    }
}
