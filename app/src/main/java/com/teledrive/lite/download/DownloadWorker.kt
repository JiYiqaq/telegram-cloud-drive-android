package com.teledrive.lite.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.teledrive.lite.R
import com.teledrive.lite.TeleDriveApplication
import com.teledrive.lite.telegram.TelegramApiException
import com.teledrive.lite.telegram.TelegramFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DownloadServices(
    val store: RoomDownloadStore,
    val coordinator: DownloadCoordinator,
)

class DownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_INVALID_TASK))
        val container = (applicationContext as TeleDriveApplication).container
        val services = container.createDownloadServices()
        if (services == null) {
            runCatching {
                container.downloadStore.markFailed(taskId, ERROR_SETUP_REQUIRED, corrupted = false)
            }
            return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_SETUP_REQUIRED))
        }
        val retryDelayMillis = try {
            services.store.retryDelayMillis(taskId)
        } catch (_: Exception) {
            return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_INVALID_TASK))
        }
        if (retryDelayMillis > 0) delay(retryDelayMillis)
        val initial = try {
            services.store.load(taskId)
        } catch (_: Exception) {
            return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_INVALID_TASK))
        }
        createNotificationChannel()
        setForeground(notification(initial, null))

        return try {
            services.store.markRunning(taskId)
            val context = currentCoroutineContext()
            services.coordinator.execute(
                taskId = taskId,
                ensureActive = { context.ensureActive() },
                onProgress = { progress ->
                    setProgress(progress.toWorkData())
                    setForeground(notification(initial, progress))
                },
            )
            Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                runCatching { services.store.markWaitingForNetwork(taskId) }
            }
            throw error
        } catch (error: TelegramApiException) {
            handleTelegramFailure(services.store, taskId, error.failure)
        } catch (error: DownloadException) {
            val corrupted = error.failure in CORRUPTING_FAILURES
            markFailed(services.store, taskId, "DOWNLOAD_${error.failure.name}", corrupted)
        } catch (_: Exception) {
            markFailed(services.store, taskId, ERROR_DOWNLOAD_FAILED, corrupted = false)
        }
    }

    private suspend fun handleTelegramFailure(
        store: RoomDownloadStore,
        taskId: String,
        failure: TelegramFailure,
    ): Result {
        val retryAfter = (failure as? TelegramFailure.Api)?.retryAfterSeconds
        if (retryAfter != null && retryAfter > 0) {
            val retryAt = System.currentTimeMillis() + retryAfter * 1_000
            runCatching { store.markRetry(taskId, retryAt, ERROR_RATE_LIMITED) }
            setProgress(
                workDataOf(
                    KEY_ERROR_CODE to ERROR_RATE_LIMITED,
                    KEY_RETRY_AT to retryAt,
                ),
            )
            return Result.retry()
        }
        if (failure is TelegramFailure.Network) {
            runCatching { store.markWaitingForNetwork(taskId) }
            return Result.retry()
        }
        return markFailed(store, taskId, ERROR_TELEGRAM_REJECTED, corrupted = false)
    }

    private suspend fun markFailed(
        store: RoomDownloadStore,
        taskId: String,
        code: String,
        corrupted: Boolean,
    ): Result {
        runCatching { store.markFailed(taskId, code, corrupted) }
        return Result.failure(workDataOf(KEY_ERROR_CODE to code))
    }

    private fun notification(
        file: DownloadFileSnapshot,
        progress: DownloadProgress?,
    ): ForegroundInfo {
        val completedBytes = progress?.completedBytes ?: 0
        val percent = when {
            file.sizeBytes == 0L && (progress?.completedChunks ?: 0) > 0 -> 100
            file.sizeBytes == 0L -> 0
            else -> ((completedBytes * 100) / file.sizeBytes).toInt().coerceIn(0, 100)
        }
        val chunkText = if (progress == null) {
            applicationContext.getString(R.string.download_preparing)
        } else {
            applicationContext.getString(
                R.string.download_chunk_progress,
                progress.completedChunks,
                progress.totalChunks,
                percent,
            )
        }
        val completedText = Formatter.formatShortFileSize(applicationContext, completedBytes)
        val totalText = Formatter.formatShortFileSize(applicationContext, file.sizeBytes)
        val speedText = Formatter.formatShortFileSize(
            applicationContext,
            progress?.speedBytesPerSecond ?: 0,
        )
        val detailText = applicationContext.getString(
            R.string.download_notification_detail,
            chunkText,
            completedText,
            totalText,
            speedText,
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(file.fileName)
            .setContentText(detailText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                applicationContext.getString(R.string.cancel),
                DownloadCancelReceiver.pendingIntent(applicationContext, file.taskId),
            )
            .build()
        val notificationId = NOTIFICATION_ID_BASE + (file.taskId.hashCode() and 0x0fff)
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

    private fun DownloadProgress.toWorkData() = workDataOf(
        KEY_COMPLETED_BYTES to completedBytes,
        KEY_TOTAL_BYTES to totalBytes,
        KEY_COMPLETED_CHUNKS to completedChunks,
        KEY_TOTAL_CHUNKS to totalChunks,
        KEY_SPEED_BYTES_PER_SECOND to speedBytesPerSecond,
    )

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_RETRY_AT = "retry_at"
        const val KEY_COMPLETED_BYTES = "completed_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_COMPLETED_CHUNKS = "completed_chunks"
        const val KEY_TOTAL_CHUNKS = "total_chunks"
        const val KEY_SPEED_BYTES_PER_SECOND = "speed_bytes_per_second"

        private const val NOTIFICATION_CHANNEL_ID = "teledrive_downloads"
        private const val NOTIFICATION_ID_BASE = 8_000
        private const val ERROR_INVALID_TASK = "INVALID_DOWNLOAD_TASK"
        private const val ERROR_SETUP_REQUIRED = "DOWNLOAD_SETUP_REQUIRED"
        private const val ERROR_DOWNLOAD_FAILED = "DOWNLOAD_FAILED"
        private const val ERROR_RATE_LIMITED = "TELEGRAM_RATE_LIMITED"
        private const val ERROR_TELEGRAM_REJECTED = "DOWNLOAD_TELEGRAM_REJECTED"
        private val CORRUPTING_FAILURES = setOf(
            DownloadFailure.AUTHENTICATION_FAILED,
            DownloadFailure.INTEGRITY_FAILED,
            DownloadFailure.REMOTE_SIZE_MISMATCH,
        )
    }
}
