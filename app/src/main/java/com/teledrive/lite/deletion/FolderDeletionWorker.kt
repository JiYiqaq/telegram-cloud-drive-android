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
import kotlinx.coroutines.CancellationException

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
        if (services == null) {
            restoreDeletionFiles(container.fileRepository, folderId, ERROR_SETUP_REQUIRED)
            return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_SETUP_REQUIRED))
        }
        var activeFileId: String? = null
        return try {
            val plan = services.repository.planFolderDeletion(folderId, confirmed = true)
            for (fileId in plan.fileIds) {
                activeFileId = fileId
                val start = services.repository.beginFileDeletion(fileId)
                start.canceledWorkRequestIds.forEach { id ->
                    runCatching {
                        WorkManager.getInstance(applicationContext)
                            .cancelWorkById(UUID.fromString(id))
                    }
                }
                if (services.coordinator.execute(fileId) is DeletionOutcome.PartiallyDeleted) {
                    activeFileId = null
                    return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_PARTIAL_DELETE))
                }
                activeFileId = null
            }
            plan.folderIdsInDeletionOrder.forEach { services.repository.deleteEmptyFolder(it) }
            services.updater.resumeOrStart()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            activeFileId?.let { fileId ->
                runCatching {
                    services.repository.markFileDeletionRecoverable(fileId, ERROR_FOLDER_DELETE_FAILED)
                }
            }
            Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_FOLDER_DELETE_FAILED))
        }
    }

    private suspend fun restoreDeletionFiles(
        repository: FileRepository,
        folderId: String,
        errorCode: String,
    ) {
        runCatching {
            repository.planFolderDeletion(folderId, confirmed = true).fileIds.forEach { fileId ->
                runCatching { repository.markFileDeletionRecoverable(fileId, errorCode) }
            }
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
