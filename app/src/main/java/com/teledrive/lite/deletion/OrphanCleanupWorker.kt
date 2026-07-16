package com.teledrive.lite.deletion

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.teledrive.lite.TeleDriveApplication

class OrphanCleanupWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_INVALID_TASK))
        val services = (applicationContext as TeleDriveApplication)
            .container
            .createOrphanCleanupServices()
            ?: return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_SETUP_REQUIRED))
        return try {
            when (val outcome = services.coordinator.execute(taskId)) {
                DeletionOutcome.Completed -> Result.success()
                is DeletionOutcome.PartiallyDeleted -> Result.failure(
                    workDataOf(
                        KEY_ERROR_CODE to ERROR_PARTIAL_CLEANUP,
                        KEY_FAILED_CHUNKS to outcome.failedChunks,
                    ),
                )
            }
        } catch (_: Exception) {
            Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_CLEANUP_FAILED))
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_FAILED_CHUNKS = "failed_chunks"
        private const val ERROR_INVALID_TASK = "INVALID_ORPHAN_TASK"
        private const val ERROR_SETUP_REQUIRED = "ORPHAN_CLEANUP_SETUP_REQUIRED"
        private const val ERROR_PARTIAL_CLEANUP = "ORPHAN_CLEANUP_PARTIAL"
        private const val ERROR_CLEANUP_FAILED = "ORPHAN_CLEANUP_FAILED"
    }
}
