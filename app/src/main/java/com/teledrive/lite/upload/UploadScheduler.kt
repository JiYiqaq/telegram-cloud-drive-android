package com.teledrive.lite.upload

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.teledrive.lite.model.TransferStatus
import java.util.UUID
import java.util.concurrent.TimeUnit

class UploadScheduler(
    context: Context,
    private val queueRepository: UploadQueueRepository,
    private val uploadStore: RoomUploadStore,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    private val contentResolver: ContentResolver = context.applicationContext.contentResolver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isNetworkAvailable: () -> Boolean = {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    },
) {
    suspend fun enqueue(
        uri: Uri,
        parentFolderId: String,
        chunkSizeBytes: Int,
    ): QueuedUpload {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val metadata = readMetadata(uri)
        val workId = UUID.randomUUID()
        val queued = queueRepository.enqueue(
            selection = UploadSelection(
                sourceUri = uri.toString(),
                displayName = metadata.displayName,
                mimeType = contentResolver.getType(uri).orEmpty(),
                sizeBytes = metadata.sizeBytes,
                createdAtEpochMillis = metadata.modifiedAtEpochMillis,
                modifiedAtEpochMillis = metadata.modifiedAtEpochMillis,
                parentFolderId = parentFolderId,
                chunkSizeBytes = chunkSizeBytes,
            ),
            workRequestId = workId.toString(),
        )
        if (!isNetworkAvailable()) uploadStore.markWaitingForNetwork(queued.taskId)
        try {
            enqueueWork(queued.taskId, workId)
        } catch (error: Exception) {
            runCatching {
                uploadStore.markStopped(
                    queued.taskId,
                    TransferStatus.FAILED,
                    ERROR_WORK_ENQUEUE_FAILED,
                )
            }
            throw error
        }
        return queued
    }

    suspend fun cancel(taskId: String) {
        val workRequestId = uploadStore.cancel(taskId) ?: return
        workManager.cancelWorkById(UUID.fromString(workRequestId))
    }

    suspend fun refreshNetworkState() {
        if (!isNetworkAvailable()) uploadStore.markQueuedUploadsWaitingForNetwork()
    }

    suspend fun retry(taskId: String): QueuedUpload {
        val workId = UUID.randomUUID()
        val queued = uploadStore.prepareRetry(taskId, workId.toString())
        try {
            enqueueWork(taskId, workId)
        } catch (error: Exception) {
            runCatching {
                uploadStore.markStopped(
                    taskId,
                    TransferStatus.FAILED,
                    ERROR_WORK_ENQUEUE_FAILED,
                )
            }
            throw error
        }
        return queued
    }

    private fun enqueueWork(taskId: String, workId: UUID) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setId(workId)
            .setInputData(
                Data.Builder()
                    .putString(UploadWorker.KEY_TASK_ID, taskId)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UPLOAD_TAG)
            .addTag("upload:$taskId")
            .build()
        workManager.beginUniqueWork(
            SERIAL_UPLOAD_QUEUE,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        ).enqueue()
    }

    private fun readMetadata(uri: Uri): SelectedDocumentMetadata {
        val now = clock()
        val extendedProjection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val basicProjection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return runCatching { queryMetadata(uri, extendedProjection, now) }
            .getOrNull()
            ?: queryMetadata(uri, basicProjection, now)
    }

    private fun queryMetadata(
        uri: Uri,
        projection: Array<String>,
        fallbackModifiedAt: Long,
    ): SelectedDocumentMetadata =
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst())
            val displayName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME)
                ?: "未命名文件"
            val size = cursor.longOrNull(OpenableColumns.SIZE)
                ?: throw IllegalArgumentException("Selected provider did not report file size")
            val modified = cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                ?.takeIf { it > 0 }
                ?: fallbackModifiedAt
            SelectedDocumentMetadata(displayName, size, modified)
        } ?: throw IllegalArgumentException("Unable to query selected file")

    private fun android.database.Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private data class SelectedDocumentMetadata(
        val displayName: String,
        val sizeBytes: Long,
        val modifiedAtEpochMillis: Long,
    )

    companion object {
        const val SERIAL_UPLOAD_QUEUE = "teledrive_serial_upload_queue_v1"
        const val UPLOAD_TAG = "teledrive_upload"
        private const val ERROR_WORK_ENQUEUE_FAILED = "WORK_ENQUEUE_FAILED"
    }
}
