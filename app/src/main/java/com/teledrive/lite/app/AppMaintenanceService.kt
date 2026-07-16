package com.teledrive.lite.app

import androidx.work.WorkManager
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.repository.FileRepository
import com.teledrive.lite.settings.AtomicSetupStateStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppMaintenanceService(
    private val database: TeleDriveDatabase,
    private val fileRepository: FileRepository,
    private val setupStateStore: AtomicSetupStateStore,
    private val workManager: WorkManager,
    private val indexCandidateDirectory: File,
) {
    suspend fun clearLocalCache() = withContext(Dispatchers.IO) {
        workManager.cancelAllWork().result.get()
        database.clearAllTables()
        clearIndexArtifacts()
        fileRepository.initializeRoot()
    }

    suspend fun logoutAndClearCredentials() = withContext(Dispatchers.IO) {
        workManager.cancelAllWork().result.get()
        database.clearAllTables()
        clearIndexArtifacts()
        setupStateStore.clear()
    }

    private fun clearIndexArtifacts() {
        indexCandidateDirectory.listFiles()?.forEach { file ->
            if (file.isFile) check(file.delete())
        }
    }
}
