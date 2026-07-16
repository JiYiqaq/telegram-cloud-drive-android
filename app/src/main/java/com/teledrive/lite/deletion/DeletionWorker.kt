package com.teledrive.lite.deletion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.teledrive.lite.R
import com.teledrive.lite.TeleDriveApplication
import com.teledrive.lite.telegram.TelegramApiException
import com.teledrive.lite.telegram.TelegramFailure

data class DeletionServices(
    val coordinator: SafeDeletionCoordinator,
)

class DeletionWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val fileId = inputData.getString(KEY_FILE_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_INVALID_FILE))
        val services = (applicationContext as TeleDriveApplication)
            .container
            .createDeletionServices()
            ?: return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_SETUP_REQUIRED))
        createNotificationChannel()
        setForeground(notification(fileId))
        return try {
            when (val outcome = services.coordinator.execute(fileId)) {
                DeletionOutcome.Completed -> Result.success()
                is DeletionOutcome.PartiallyDeleted -> Result.failure(
                    workDataOf(
                        KEY_ERROR_CODE to ERROR_PARTIALLY_DELETED,
                        KEY_FAILED_CHUNKS to outcome.failedChunks,
                    ),
                )
            }
        } catch (error: TelegramApiException) {
            val retryAfter = (error.failure as? TelegramFailure.Api)?.retryAfterSeconds
            if (error.failure is TelegramFailure.Network || retryAfter?.let { it > 0 } == true) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_INDEX_PUBLICATION_FAILED))
            }
        } catch (_: Exception) {
            Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_DELETION_FAILED))
        }
    }

    private fun notification(fileId: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.safe_deletion_title))
            .setContentText(applicationContext.getString(R.string.safe_deletion_progress))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val notificationId = NOTIFICATION_ID_BASE + (fileId.hashCode() and 0x0fff)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
        const val KEY_FILE_ID = "file_id"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_FAILED_CHUNKS = "failed_chunks"

        private const val NOTIFICATION_CHANNEL_ID = "teledrive_deletions"
        private const val NOTIFICATION_ID_BASE = 12_000
        private const val ERROR_INVALID_FILE = "INVALID_DELETE_FILE"
        private const val ERROR_SETUP_REQUIRED = "DELETE_SETUP_REQUIRED"
        private const val ERROR_PARTIALLY_DELETED = "PARTIALLY_DELETED"
        private const val ERROR_INDEX_PUBLICATION_FAILED = "DELETE_INDEX_PUBLICATION_FAILED"
        private const val ERROR_DELETION_FAILED = "DELETE_FAILED"
    }
}
