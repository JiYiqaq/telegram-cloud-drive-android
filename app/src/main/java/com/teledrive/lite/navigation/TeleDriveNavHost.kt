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
import com.teledrive.lite.ui.settings.SettingsRoute
import com.teledrive.lite.ui.settings.SettingsViewModel
import com.teledrive.lite.ui.settings.SettingsViewModelFactory
import com.teledrive.lite.ui.recovery.RecoveryRoute
import com.teledrive.lite.ui.recovery.RecoveryViewModel
import com.teledrive.lite.ui.recovery.RecoveryViewModelFactory
import com.teledrive.lite.ui.about.AboutScreen
import com.teledrive.lite.ui.settings.ConnectionUpdateRoute
import com.teledrive.lite.ui.settings.ConnectionUpdateViewModel
import com.teledrive.lite.ui.settings.ConnectionUpdateViewModelFactory
import com.teledrive.lite.ui.detail.FileDetailRoute
import com.teledrive.lite.ui.detail.FileDetailViewModel
import com.teledrive.lite.ui.detail.FileDetailViewModelFactory

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
                    preferences = container.appPreferences,
                    indexUpdater = { container.createCloudIndexServices()?.updater },
                    folderDeletionScheduler = container.folderDeletionScheduler,
                ),
            )
            HomeRoute(
                viewModel = homeViewModel,
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onOpenFileDetail = { navController.navigate(Routes.fileDetail(it)) },
            )
        }
        composable(Routes.Settings) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(
                    settingsRepository = container.settingsRepository,
                    preferences = container.appPreferences,
                    secureConfigStore = container.secureConfigStore,
                    connectionService = container.setupConnectionService,
                    indexUpdater = { container.createCloudIndexServices()?.updater },
                    maintenanceService = container.maintenanceService,
                    contentResolver = container.applicationContext.contentResolver,
                ),
            )
            SettingsRoute(
                viewModel = settingsViewModel,
                settingsRepository = container.settingsRepository,
                onBack = navController::popBackStack,
                onRecovery = { navController.navigate(Routes.Recovery) },
                onConnection = { navController.navigate(Routes.Connection) },
                onAbout = { navController.navigate(Routes.About) },
                onLoggedOut = {
                    navController.navigate(Routes.Splash) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.Recovery) {
            val recoveryViewModel: RecoveryViewModel = viewModel(
                factory = RecoveryViewModelFactory {
                    container.createCloudIndexServices()?.recovery
                },
            )
            RecoveryRoute(
                viewModel = recoveryViewModel,
                onBack = navController::popBackStack,
                onRecovered = {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Home) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.About) {
            AboutScreen(onBack = navController::popBackStack)
        }
        composable(Routes.Connection) {
            val updateViewModel: ConnectionUpdateViewModel = viewModel(
                factory = ConnectionUpdateViewModelFactory(
                    container.setupConnectionService,
                    container.secureConfigStore,
                ),
            )
            ConnectionUpdateRoute(updateViewModel, navController::popBackStack)
        }
        composable(Routes.FileDetailPattern) { backStackEntry ->
            val fileId = requireNotNull(backStackEntry.arguments?.getString("fileId"))
            val detailViewModel: FileDetailViewModel = viewModel(
                factory = FileDetailViewModelFactory(container.fileRepository),
            )
            FileDetailRoute(fileId, detailViewModel, navController::popBackStack)
        }
    }
}
