package com.teledrive.lite.deletion

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.teledrive.lite.repository.FileDeletionStart
import com.teledrive.lite.repository.FileRepository
import java.util.UUID
import java.util.concurrent.TimeUnit

class DeletionScheduler(
    context: Context,
    private val repository: FileRepository,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) {
    suspend fun enqueue(fileId: String) = enqueueBatch(listOf(fileId))

    suspend fun enqueueBatch(fileIds: List<String>) {
        val starts = repository.beginFileDeletions(fileIds)
        if (starts.isEmpty()) return
        starts.flatMap(FileDeletionStart::canceledWorkRequestIds).forEach { workId ->
            runCatching { workManager.cancelWorkById(UUID.fromString(workId)) }
        }
        val operation = try {
            val request = batchDeletionRequest(starts)
            workManager.beginUniqueWork(
                SERIAL_DELETION_QUEUE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            ).enqueue()
        } catch (error: Exception) {
            try {
                DeletionWorkPersistence.restore { recoverAll(starts) }
            } catch (recoveryError: Exception) {
                error.addSuppressed(recoveryError)
            }
            throw error
        }
        DeletionWorkPersistence.await(
            waitForPersistence = { operation.result.get(); Unit },
            recover = { recoverAll(starts) },
        )
    }

    private fun batchDeletionRequest(starts: List<FileDeletionStart>) =
        OneTimeWorkRequestBuilder<BatchDeletionWorker>()
            .setInputData(
                Data.Builder()
                    .putStringArray(
                        BatchDeletionWorker.KEY_FILE_IDS,
                        starts.map(FileDeletionStart::fileId).toTypedArray(),
                    )
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DELETION_TAG)
            .build()

    private suspend fun recoverAll(starts: List<FileDeletionStart>) {
        var firstError: Exception? = null
        starts.forEach { start ->
            try {
                repository.markFileDeletionRecoverable(start.fileId, ERROR_ENQUEUE_FAILED)
            } catch (error: Exception) {
                if (firstError == null) {
                    firstError = error
                } else {
                    checkNotNull(firstError).addSuppressed(error)
                }
            }
        }
        firstError?.let { throw it }
    }

    companion object {
        const val DELETION_TAG = "teledrive_safe_deletion"
        const val SERIAL_DELETION_QUEUE = "teledrive_serial_deletion_queue_v2"
        private const val ERROR_ENQUEUE_FAILED = "DELETE_WORK_NOT_ENQUEUED"
    }
}
