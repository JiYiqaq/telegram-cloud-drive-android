package com.teledrive.lite.download

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

class DownloadScheduler(
    context: Context,
    private val queueRepository: DownloadQueueRepository,
    private val downloadStore: RoomDownloadStore,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    private val contentResolver: ContentResolver = context.applicationContext.contentResolver,
    private val isNetworkAvailable: () -> Boolean = {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    },
) {
    suspend fun enqueue(fileId: String, destinationUri: Uri): QueuedDownload {
        require(destinationUri.scheme == ContentResolver.SCHEME_CONTENT)
        runCatching {
            contentResolver.takePersistableUriPermission(
                destinationUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val workId = UUID.randomUUID()
        val queued = try {
            queueRepository.enqueue(fileId, destinationUri.toString(), workId.toString())
        } catch (error: Exception) {
            runCatching { contentResolver.delete(destinationUri, null, null) }
            throw error
        }
        if (!isNetworkAvailable()) downloadStore.markWaitingForNetwork(queued.taskId)
        try {
            enqueueWork(queued.taskId, workId)
        } catch (error: Exception) {
            runCatching {
                downloadStore.markFailed(
                    queued.taskId,
                    ERROR_WORK_ENQUEUE_FAILED,
                    corrupted = false,
                )
            }
            runCatching { contentResolver.delete(destinationUri, null, null) }
            throw error
        }
        return queued
    }

    suspend fun cancel(taskId: String) {
        val workRequestId = downloadStore.cancel(taskId) ?: return
        workManager.cancelWorkById(UUID.fromString(workRequestId))
    }

    suspend fun retry(taskId: String): QueuedDownload {
        val workId = UUID.randomUUID()
        val queued = downloadStore.prepareRetry(taskId, workId.toString())
        if (!isNetworkAvailable()) downloadStore.markWaitingForNetwork(taskId)
        try {
            enqueueWork(taskId, workId)
        } catch (error: Exception) {
            runCatching {
                downloadStore.markFailed(taskId, ERROR_WORK_ENQUEUE_FAILED, corrupted = false)
            }
            throw error
        }
        return queued
    }

    suspend fun refreshNetworkState() {
        if (!isNetworkAvailable()) downloadStore.markQueuedDownloadsWaitingForNetwork()
    }

    private fun enqueueWork(taskId: String, workId: UUID) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setId(workId)
            .setInputData(
                Data.Builder()
                    .putString(DownloadWorker.KEY_TASK_ID, taskId)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DOWNLOAD_TAG)
            .addTag("download:$taskId")
            .build()
        workManager.beginUniqueWork(
            SERIAL_DOWNLOAD_QUEUE,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        ).enqueue()
    }

    companion object {
        const val SERIAL_DOWNLOAD_QUEUE = "teledrive_serial_download_queue_v1"
        const val DOWNLOAD_TAG = "teledrive_download"
        private const val ERROR_WORK_ENQUEUE_FAILED = "DOWNLOAD_WORK_ENQUEUE_FAILED"
    }
}
