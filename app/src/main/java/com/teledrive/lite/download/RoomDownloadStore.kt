package com.teledrive.lite.download

import androidx.room.withTransaction
import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.database.FileEntity
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.database.TransferTaskEntity
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType
import java.util.UUID

data class QueuedDownload(
    val taskId: String,
    val fileId: String,
    val workRequestId: String,
)

class DownloadQueueRepository(
    private val database: TeleDriveDatabase,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun enqueue(
        fileId: String,
        destinationUri: String,
        workRequestId: String,
    ): QueuedDownload = database.withTransaction {
        require(fileId.isNotBlank() && destinationUri.startsWith("content://"))
        require(runCatching { UUID.fromString(workRequestId) }.isSuccess)
        val file = requireNotNull(database.fileDao().getById(fileId))
        require(file.isCloudIndexed && file.status in DOWNLOADABLE_FILE_STATES)
        requireValidCloudFile(file)
        require(database.transferTaskDao().countActiveForFile(fileId) == 0)
        val taskId = idGenerator()
        require(taskId.isNotBlank())
        val chunks = database.chunkDao().getForFile(file.id)
        require(
            chunks.size == file.chunkCount && chunks.withIndex().all { (index, chunk) ->
                chunk.partIndex == index &&
                    chunk.uploadStatus == ChunkUploadStatus.UPLOADED &&
                    chunk.messageId != null &&
                    !chunk.telegramFileId.isNullOrBlank() &&
                    chunk.nonce?.size == NONCE_BYTES
            },
        )
        val now = clock()
        database.transferTaskDao().upsert(
            TransferTaskEntity(
                id = taskId,
                fileId = file.id,
                fileNameSnapshot = file.name,
                type = TransferType.DOWNLOAD,
                status = TransferStatus.QUEUED,
                completedBytes = 0,
                totalBytes = file.sizeBytes,
                currentChunk = 0,
                totalChunks = file.chunkCount,
                speedBytesPerSecond = 0,
                attempt = 0,
                nextRetryAtEpochMillis = null,
                errorCode = null,
                workRequestId = workRequestId,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                destinationUri = destinationUri,
                previousFileStatus = file.status,
            ),
        )
        require(database.fileDao().updateStatus(file.id, FileStatus.DOWNLOADING, now) == 1)
        QueuedDownload(taskId, file.id, workRequestId)
    }

    private fun requireValidCloudFile(file: FileEntity) {
        require(file.sha256?.matches(SHA_256) == true)
        require(file.wrappedDataKey?.size == CryptoEngine.DATA_KEY_BYTES + CryptoEngine.ENVELOPE_OVERHEAD_BYTES)
        require(file.chunkCount > 0 && file.chunkSizeBytes > 0 && file.sizeBytes >= 0)
    }

    private companion object {
        const val NONCE_BYTES = 12
        val DOWNLOADABLE_FILE_STATES = setOf(FileStatus.AVAILABLE, FileStatus.CORRUPTED)
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

class RoomDownloadStore(
    private val database: TeleDriveDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : DownloadStore {
    override suspend fun load(taskId: String): DownloadFileSnapshot = database.withTransaction {
        val task = requireDownloadTask(taskId)
        val file = requireNotNull(task.fileId?.let { database.fileDao().getById(it) })
        require(file.isCloudIndexed && file.status == FileStatus.DOWNLOADING)
        val chunks = database.chunkDao().getForFile(file.id)
        require(chunks.size == file.chunkCount)
        DownloadFileSnapshot(
            taskId = task.id,
            fileId = file.id,
            fileName = file.name,
            destinationUri = requireNotNull(task.destinationUri),
            sizeBytes = file.sizeBytes,
            chunkSizeBytes = file.chunkSizeBytes,
            chunkCount = file.chunkCount,
            sha256 = requireNotNull(file.sha256),
            wrappedDataKey = requireNotNull(file.wrappedDataKey).copyOf(),
            chunks = chunks.map { chunk ->
                require(chunk.uploadStatus == ChunkUploadStatus.UPLOADED)
                DownloadChunkSnapshot(
                    partIndex = chunk.partIndex,
                    telegramFileId = requireNotNull(chunk.telegramFileId),
                    nonce = requireNotNull(chunk.nonce).copyOf(),
                    encryptedSizeBytes = chunk.encryptedSizeBytes,
                )
            },
        )
    }

    override suspend fun updateProgress(taskId: String, progress: DownloadProgress) =
        database.withTransaction {
            val task = requireDownloadTask(taskId)
            require(task.status == TransferStatus.RUNNING)
            require(progress.totalBytes == task.totalBytes && progress.totalChunks == task.totalChunks)
            require(progress.completedBytes >= task.completedBytes)
            require(progress.completedChunks >= task.currentChunk)
            require(
                database.transferTaskDao().updateProgress(
                    task.id,
                    progress.completedBytes,
                    progress.completedChunks,
                    progress.speedBytesPerSecond,
                    clock(),
                ) == 1,
            )
        }

    override suspend fun finalizeAfterDestination(taskId: String) = database.withTransaction {
        val task = requireDownloadTask(taskId)
        require(
            task.status == TransferStatus.RUNNING &&
                task.completedBytes == task.totalBytes &&
                task.currentChunk == task.totalChunks
        )
        val fileId = requireNotNull(task.fileId)
        val now = clock()
        require(database.fileDao().updateStatus(fileId, FileStatus.AVAILABLE, now) == 1)
        require(
            database.transferTaskDao().updateStatus(
                task.id,
                TransferStatus.SUCCESS,
                null,
                0,
                now,
            ) == 1,
        )
    }

    suspend fun markRunning(taskId: String) = database.withTransaction {
        val task = requireDownloadTask(taskId)
        require(task.status in STARTABLE_STATES)
        require(
            database.transferTaskDao().updateStatus(
                task.id,
                TransferStatus.RUNNING,
                null,
                0,
                clock(),
            ) == 1,
        )
    }

    suspend fun markFailed(taskId: String, errorCode: String, corrupted: Boolean) =
        database.withTransaction {
            require(errorCode.isNotBlank())
            val task = requireDownloadTask(taskId)
            if (task.status in TERMINAL_STATES) return@withTransaction
            val fileId = requireNotNull(task.fileId)
            val restored = if (corrupted) {
                FileStatus.CORRUPTED
            } else {
                requireNotNull(task.previousFileStatus)
            }
            val now = clock()
            require(database.fileDao().updateStatus(fileId, restored, now) == 1)
            require(
                database.transferTaskDao().updateStatus(
                    task.id,
                    TransferStatus.FAILED,
                    errorCode,
                    0,
                    now,
                ) == 1,
            )
        }

    suspend fun markWaitingForNetwork(taskId: String) = database.withTransaction {
        val task = requireDownloadTask(taskId)
        if (task.status in TERMINAL_STATES) return@withTransaction
        require(
            database.transferTaskDao().updateStatus(
                task.id,
                TransferStatus.WAITING_FOR_NETWORK,
                null,
                0,
                clock(),
            ) == 1,
        )
    }

    suspend fun markRetry(taskId: String, retryAtEpochMillis: Long, errorCode: String) =
        database.withTransaction {
            require(errorCode.isNotBlank())
            val task = requireDownloadTask(taskId)
            require(task.status !in TERMINAL_STATES)
            require(retryAtEpochMillis > clock())
            require(
                database.transferTaskDao().scheduleRetry(
                    task.id,
                    retryAtEpochMillis,
                    errorCode,
                    clock(),
                ) == 1,
            )
        }

    suspend fun retryDelayMillis(taskId: String): Long {
        val task = requireDownloadTask(taskId)
        return if (task.status == TransferStatus.WAITING_FOR_RETRY) {
            (task.nextRetryAtEpochMillis ?: 0L).minus(clock()).coerceAtLeast(0)
        } else {
            0
        }
    }

    suspend fun cancel(taskId: String): String? = database.withTransaction {
        val task = requireDownloadTask(taskId)
        if (task.status == TransferStatus.CANCELED) return@withTransaction task.workRequestId
        require(task.status !in setOf(TransferStatus.SUCCESS, TransferStatus.FAILED))
        val fileId = requireNotNull(task.fileId)
        val now = clock()
        require(
            database.fileDao().updateStatus(fileId, requireNotNull(task.previousFileStatus), now) == 1,
        )
        require(
            database.transferTaskDao().updateStatus(
                task.id,
                TransferStatus.CANCELED,
                USER_CANCELED_ERROR,
                0,
                now,
            ) == 1,
        )
        task.workRequestId
    }

    suspend fun prepareRetry(taskId: String, workRequestId: String): QueuedDownload =
        database.withTransaction {
            require(runCatching { UUID.fromString(workRequestId) }.isSuccess)
            val task = requireDownloadTask(taskId)
            require(DownloadRetryPolicy.canRetry(task))
            val fileId = requireNotNull(task.fileId)
            val now = clock()
            require(database.fileDao().updateStatus(fileId, FileStatus.DOWNLOADING, now) == 1)
            require(
                database.transferTaskDao().restartWithWorkRequest(task.id, workRequestId, now) == 1,
            )
            QueuedDownload(task.id, fileId, workRequestId)
        }

    suspend fun markQueuedDownloadsWaitingForNetwork(): Int = database.withTransaction {
        database.transferTaskDao().markQueuedDownloadsWaitingForNetwork(clock())
    }

    private suspend fun requireDownloadTask(taskId: String): TransferTaskEntity =
        requireNotNull(database.transferTaskDao().getById(taskId)).also {
            require(it.type == TransferType.DOWNLOAD)
        }

    private companion object {
        const val USER_CANCELED_ERROR = "USER_CANCELED"
        val STARTABLE_STATES = setOf(
            TransferStatus.QUEUED,
            TransferStatus.WAITING_FOR_NETWORK,
            TransferStatus.WAITING_FOR_RETRY,
            TransferStatus.RUNNING,
        )
        val TERMINAL_STATES = setOf(
            TransferStatus.SUCCESS,
            TransferStatus.FAILED,
            TransferStatus.CANCELED,
        )
    }
}
