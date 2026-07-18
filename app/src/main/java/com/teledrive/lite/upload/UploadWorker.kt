package com.teledrive.lite.upload

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
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.telegram.TelegramApiException
import com.teledrive.lite.telegram.TelegramFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class UploadServices(
    val store: RoomUploadStore,
    val coordinator: UploadCoordinator,
)

object UploadWorkerFailurePolicy {
    const val INVALID_TASK_ERROR = "INVALID_UPLOAD_TASK"

    fun loadErrorCode(error: Exception): String =
        if (error is UploadException) {
            "UPLOAD_${error.failure.name}"
        } else {
            INVALID_TASK_ERROR
        }
}

class UploadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_INVALID_TASK))
        val workRequestId = id.toString()
        val container = (applicationContext as TeleDriveApplication).container
        val services = container.createUploadServices()
        if (services == null) {
            runCatching {
                container.uploadStore.markStoppedForWork(
                    taskId,
                    workRequestId,
                    TransferStatus.FAILED,
                    ERROR_SETUP_REQUIRED,
                )
            }
            return Result.failure(workDataOf(KEY_ERROR_CODE to ERROR_SETUP_REQUIRED))
        }
        val initial = try {
            services.store.loadForWorker(taskId, workRequestId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return markFailed(
                services.store,
                taskId,
                workRequestId,
                UploadWorkerFailurePolicy.loadErrorCode(error),
            )
        }
        if (initial == null) return Result.success()
        createNotificationChannel()
        // Waiting uploads are foreground work too, so a large predecessor cannot exhaust their
        // ordinary ten-minute WorkManager execution window before they acquire the serial gate.
        setForeground(notification(initial.file, null))

        val retryDelayMillis = try {
            services.store.retryDelayMillis(taskId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return markFailed(
                services.store,
                taskId,
                workRequestId,
                UploadWorkerFailurePolicy.loadErrorCode(error),
            )
        }
        if (retryDelayMillis > 0) delay(retryDelayMillis)

        return UploadExecutionGate.processWide.withPermit {
            executeUpload(taskId, workRequestId, services, initial)
        }
    }

    private suspend fun executeUpload(
        taskId: String,
        workRequestId: String,
        services: UploadServices,
        initial: UploadResumeState,
    ): Result {
        return try {
            if (!services.store.markRunning(taskId, workRequestId)) {
                return Result.success()
            }
            val context = currentCoroutineContext()
            services.coordinator.execute(
                taskId = taskId,
                ensureActive = { context.ensureActive() },
                onProgress = { progress ->
                    setProgress(progress.toWorkData())
                    setForeground(notification(initial.file, progress))
                },
            )
            Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    services.store.markStoppedForWork(
                        taskId,
                        workRequestId,
                        TransferStatus.WAITING_FOR_NETWORK,
                        null,
                    )
                }
            }
            throw error
        } catch (error: TelegramApiException) {
            handleTelegramFailure(services.store, taskId, workRequestId, error.failure)
        } catch (error: UploadException) {
            markFailed(
                services.store,
                taskId,
                workRequestId,
                "UPLOAD_${error.failure.name}",
            )
        } catch (_: Exception) {
            markFailed(services.store, taskId, workRequestId, ERROR_UPLOAD_FAILED)
        }
    }

    private suspend fun handleTelegramFailure(
        store: RoomUploadStore,
        taskId: String,
        workRequestId: String,
        failure: TelegramFailure,
    ): Result {
        val retryAfter = (failure as? TelegramFailure.Api)?.retryAfterSeconds
        if (retryAfter != null && retryAfter > 0) {
            val retryAt = System.currentTimeMillis() + retryAfter * 1_000
            val retryRecorded = runCatching {
                store.markRetryForWork(
                    taskId,
                    workRequestId,
                    retryAt,
                    ERROR_RATE_LIMITED,
                )
            }.getOrDefault(false)
            if (!retryRecorded) return Result.success()
            setProgress(
                workDataOf(
                    KEY_ERROR_CODE to ERROR_RATE_LIMITED,
                    KEY_RETRY_AT to retryAt,
                ),
            )
            return Result.retry()
        }
        val code = if (failure is TelegramFailure.Network) {
            // A timed-out sendDocument may already have created a message; never retry blindly.
            UploadRetryPolicy.RESULT_UNKNOWN_ERROR
        } else {
            ERROR_TELEGRAM_REJECTED
        }
        return markFailed(store, taskId, workRequestId, code)
    }

    private suspend fun markFailed(
        store: RoomUploadStore,
        taskId: String,
        workRequestId: String,
        code: String,
    ): Result {
        val recorded = try {
            store.markStoppedForWork(
                taskId,
                workRequestId,
                TransferStatus.FAILED,
                code,
            )
        } catch (_: Exception) {
            return Result.failure(workDataOf(KEY_ERROR_CODE to code))
        }
        return if (recorded) {
            Result.failure(workDataOf(KEY_ERROR_CODE to code))
        } else {
            Result.success()
        }
    }

    private fun notification(
        file: UploadFileSnapshot,
        progress: UploadProgress?,
    ): ForegroundInfo {
        val completedBytes = progress?.completedBytes ?: 0
        val percent = when {
            file.sizeBytes == 0L && (progress?.completedChunks ?: 0) > 0 -> 100
            file.sizeBytes == 0L -> 0
            else -> ((completedBytes * 100) / file.sizeBytes).toInt().coerceIn(0, 100)
        }
        val chunkText = if (progress == null) {
            applicationContext.getString(R.string.upload_preparing)
        } else {
            applicationContext.getString(
                R.string.upload_chunk_progress,
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
            R.string.upload_notification_detail,
            chunkText,
            completedText,
            totalText,
            speedText,
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(file.originalName)
            .setContentText(detailText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                applicationContext.getString(R.string.cancel),
                UploadCancelReceiver.pendingIntent(applicationContext, file.taskId),
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
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                applicationContext.getString(R.string.transfer_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun UploadProgress.toWorkData() = workDataOf(
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

        private const val NOTIFICATION_CHANNEL_ID = "teledrive_uploads"
        private const val NOTIFICATION_ID_BASE = 4_000
        private const val ERROR_INVALID_TASK = UploadWorkerFailurePolicy.INVALID_TASK_ERROR
        private const val ERROR_SETUP_REQUIRED = "SETUP_REQUIRED"
        private const val ERROR_UPLOAD_FAILED = "UPLOAD_FAILED"
        private const val ERROR_RATE_LIMITED = "TELEGRAM_RATE_LIMITED"
        private const val ERROR_TELEGRAM_REJECTED = "TELEGRAM_REJECTED"
    }
}
