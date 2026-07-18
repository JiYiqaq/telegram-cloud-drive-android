package com.teledrive.lite.deletion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.teledrive.lite.R
import com.teledrive.lite.TeleDriveApplication
import com.teledrive.lite.repository.DriveRepositoryException
import com.teledrive.lite.telegram.TelegramApiException
import com.teledrive.lite.telegram.TelegramFailure
import kotlinx.coroutines.CancellationException

class BatchDeletionWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val fileIds = inputData.getStringArray(KEY_FILE_IDS)
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()
        if (fileIds.isEmpty()) return Result.success()
        val container = (applicationContext as TeleDriveApplication).container
        val services = container.createDeletionServices()
        if (services == null) {
            recoverAll(fileIds, ERROR_SETUP_REQUIRED)
            return Result.success(workDataOf(KEY_FAILED_FILES to fileIds.size))
        }
        return try {
            createNotificationChannel()
            setForeground(notification())
            val result = DeletionBatchProcessor(
                execute = services.coordinator::execute,
                recover = { fileId, error ->
                    container.fileRepository.markFileDeletionRecoverable(
                        fileId,
                        errorCode(error),
                    )
                },
                retryDelaySeconds = ::retryDelaySeconds,
            ).run(fileIds)
            if (result.retryableFileIds.isNotEmpty()) {
                try {
                    enqueueRetry(
                        result.retryableFileIds,
                        checkNotNull(result.retryDelaySeconds),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return Result.retry()
                }
            }
            Result.success(
                workDataOf(
                    KEY_COMPLETED_FILES to result.completed,
                    KEY_FAILED_FILES to result.failed,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recoverAll(fileIds, errorCode(error))
            Result.success(workDataOf(KEY_FAILED_FILES to fileIds.size))
        }
    }

    private suspend fun recoverAll(fileIds: List<String>, errorCode: String) {
        fileIds.forEach { fileId ->
            try {
                (applicationContext as TeleDriveApplication).container.fileRepository
                    .markFileDeletionRecoverable(fileId, errorCode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Keep processing the remaining files so one recovery failure cannot strand the batch.
            }
        }
    }

    private suspend fun enqueueRetry(fileIds: List<String>, delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<BatchDeletionWorker>()
            .setInputData(
                Data.Builder()
                    .putStringArray(KEY_FILE_IDS, fileIds.toTypedArray())
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(delaySeconds, java.util.concurrent.TimeUnit.SECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                MINIMUM_RETRY_DELAY_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS,
            )
            .addTag(DeletionScheduler.DELETION_TAG)
            .build()
        val operation = WorkManager.getInstance(applicationContext).beginUniqueWork(
            DeletionScheduler.SERIAL_DELETION_QUEUE,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        ).enqueue()
        DeletionWorkPersistence.await(
            waitForPersistence = { operation.result.get(); Unit },
            recover = {},
        )
    }

    private fun retryDelaySeconds(error: Exception): Long? {
        val failure = (error as? TelegramApiException)?.failure ?: return null
        return when (failure) {
            is TelegramFailure.Network -> MINIMUM_RETRY_DELAY_SECONDS
            is TelegramFailure.Api -> failure.retryAfterSeconds?.coerceAtLeast(1)
            else -> null
        }
    }

    private fun errorCode(error: Exception): String = when (error) {
        is DriveRepositoryException -> "DELETE_${error.failure.name}"
        is TelegramApiException -> when (val failure = error.failure) {
            is TelegramFailure.Network -> "DELETE_NETWORK_FAILED"
            is TelegramFailure.Api -> if (failure.retryAfterSeconds != null) {
                "DELETE_RATE_LIMITED"
            } else {
                "DELETE_TELEGRAM_API_FAILED"
            }
            else -> ERROR_DELETION_FAILED
        }
        else -> ERROR_DELETION_FAILED
    }

    private fun notification(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.safe_deletion_title))
            .setContentText(applicationContext.getString(R.string.safe_deletion_progress))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                applicationContext.getString(R.string.transfer_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val KEY_FILE_IDS = "file_ids"
        const val KEY_COMPLETED_FILES = "completed_files"
        const val KEY_FAILED_FILES = "failed_files"

        private const val NOTIFICATION_CHANNEL_ID = "teledrive_deletions"
        private const val NOTIFICATION_ID = 12_001
        private const val ERROR_SETUP_REQUIRED = "DELETE_SETUP_REQUIRED"
        private const val ERROR_DELETION_FAILED = "DELETE_FAILED"
        private const val MINIMUM_RETRY_DELAY_SECONDS = 30L
    }
}
