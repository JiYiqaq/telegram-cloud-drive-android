package com.teledrive.lite.deletion

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.teledrive.lite.TeleDriveApplication
import com.teledrive.lite.repository.FileRepository
import com.teledrive.lite.sync.IndexAtomicUpdater
import java.util.UUID

data class FolderDeletionServices(
    val repository: FileRepository,
    val coordinator: SafeDeletionCoordinator,
    val updater: IndexAtomicUpdater,
)

class FolderDeletionWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val folderId = inputData.getString(KEY_FOLDER_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_INVALID_FOLDER))
        val container = (applicationContext as TeleDriveApplication).container
        val services = container.createFolderDeletionServices()
            ?: return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_SETUP_REQUIRED))
        return try {
            val plan = services.repository.planFolderDeletion(folderId, confirmed = true)
            for (fileId in plan.fileIds) {
                val start = services.repository.beginFileDeletion(fileId)
                start.canceledWorkRequestIds.forEach { id ->
                    runCatching {
                        WorkManager.getInstance(applicationContext)
                            .cancelWorkById(UUID.fromString(id))
                    }
                }
                if (services.coordinator.execute(fileId) is DeletionOutcome.PartiallyDeleted) {
                    return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_PARTIAL_DELETE))
                }
            }
            plan.folderIdsInDeletionOrder.forEach { services.repository.deleteEmptyFolder(it) }
            services.updater.resumeOrStart()
            Result.success()
        } catch (_: Exception) {
            Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_FOLDER_DELETE_FAILED))
        }
    }

    companion object {
        const val KEY_FOLDER_ID = "folder_id"
        const val KEY_ERROR_CODE = "error_code"
        private const val ERROR_INVALID_FOLDER = "INVALID_FOLDER"
        private const val ERROR_SETUP_REQUIRED = "FOLDER_DELETE_SETUP_REQUIRED"
        private const val ERROR_PARTIAL_DELETE = "FOLDER_PARTIALLY_DELETED"
        private const val ERROR_FOLDER_DELETE_FAILED = "FOLDER_DELETE_FAILED"
    }
}
