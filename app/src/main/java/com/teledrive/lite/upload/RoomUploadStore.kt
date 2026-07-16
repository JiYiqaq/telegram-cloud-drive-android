package com.teledrive.lite.upload

import androidx.room.withTransaction
import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.database.ChunkEntity
import com.teledrive.lite.database.FileEntity
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.database.TransferTaskEntity
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType
import com.teledrive.lite.repository.DriveNameValidator
import com.teledrive.lite.repository.FileChunkLayout
import com.teledrive.lite.transfer.StreamingChunker
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UploadSelection(
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAtEpochMillis: Long,
    val modifiedAtEpochMillis: Long,
    val parentFolderId: String,
    val chunkSizeBytes: Int = StreamingChunker.DEFAULT_CHUNK_SIZE_BYTES,
)

data class QueuedUpload(
    val taskId: String,
    val fileId: String,
    val workRequestId: String,
)

class UploadQueueRepository(
    private val database: TeleDriveDatabase,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun enqueue(selection: UploadSelection, workRequestId: String): QueuedUpload =
        database.withTransaction {
            validate(selection, workRequestId)
            requireNotNull(database.folderDao().getById(selection.parentFolderId))
            require(database.folderDao().countName(selection.parentFolderId, selection.displayName) == 0)
            require(database.fileDao().countName(selection.parentFolderId, selection.displayName) == 0)
            val fileId = idGenerator()
            val taskId = idGenerator()
            require(UUID.fromString(fileId).toString() == fileId)
            require(UUID.fromString(taskId).toString() == taskId)
            val chunkCount = requireNotNull(
                FileChunkLayout.expectedChunkCount(selection.sizeBytes, selection.chunkSizeBytes),
            )
            val now = clock()
            database.fileDao().upsert(
                FileEntity(
                    id = fileId,
                    name = selection.displayName,
                    mimeType = selection.mimeType.ifBlank { DEFAULT_MIME_TYPE },
                    sizeBytes = selection.sizeBytes,
                    createdAtEpochMillis = selection.createdAtEpochMillis,
                    modifiedAtEpochMillis = selection.modifiedAtEpochMillis,
                    uploadedAtEpochMillis = null,
                    parentFolderId = selection.parentFolderId,
                    sha256 = null,
                    encryptionFormatVersion = ENCRYPTION_FORMAT_VERSION,
                    chunkSizeBytes = selection.chunkSizeBytes,
                    chunkCount = chunkCount,
                    wrappedDataKey = null,
                    status = FileStatus.PENDING,
                    isCloudIndexed = false,
                ),
            )
            database.transferTaskDao().upsert(
                TransferTaskEntity(
                    id = taskId,
                    fileId = fileId,
                    fileNameSnapshot = selection.displayName,
                    type = TransferType.UPLOAD,
                    status = TransferStatus.QUEUED,
                    completedBytes = 0,
                    totalBytes = selection.sizeBytes,
                    currentChunk = 0,
                    totalChunks = chunkCount,
                    speedBytesPerSecond = 0,
                    attempt = 0,
                    nextRetryAtEpochMillis = null,
                    errorCode = null,
                    workRequestId = workRequestId,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    sourceUri = selection.sourceUri,
                ),
            )
            QueuedUpload(taskId, fileId, workRequestId)
        }

    private fun validate(selection: UploadSelection, workRequestId: String) {
        require(selection.sourceUri.startsWith("content://"))
        DriveNameValidator.requireValid(selection.displayName)
        require(selection.mimeType.length <= MAX_MIME_LENGTH)
        require(selection.sizeBytes >= 0)
        require(selection.createdAtEpochMillis >= 0)
        require(selection.modifiedAtEpochMillis >= selection.createdAtEpochMillis)
        require(selection.parentFolderId.isNotBlank())
        require(selection.chunkSizeBytes in 1..StreamingChunker.MAX_PLAINTEXT_CHUNK_SIZE_BYTES)
        require(runCatching { UUID.fromString(workRequestId) }.isSuccess)
    }

    private companion object {
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val ENCRYPTION_FORMAT_VERSION = 1
        const val MAX_MIME_LENGTH = 255
    }
}

class RoomUploadStore(
    private val database: TeleDriveDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : UploadStore {
    private val commitMutex = Mutex()

    override suspend fun load(taskId: String): UploadResumeState = database.withTransaction {
        val task = requireUploadTask(taskId)
        val file = requireNotNull(task.fileId?.let { database.fileDao().getById(it) })
        require(file.status in UPLOAD_FILE_STATES && !file.isCloudIndexed)
        val sourceUri = requireNotNull(task.sourceUri)
        val chunks = database.chunkDao().getForFile(file.id).sortedBy(ChunkEntity::partIndex)
        if (chunks.any { it.uploadStatus == ChunkUploadStatus.UPLOADING }) {
            throw UploadException(UploadFailure.REMOTE_RESULT_UNKNOWN)
        }
        val hasSecurity = file.sha256 != null || file.wrappedDataKey != null
        if (!hasSecurity) require(chunks.isEmpty())
        if (hasSecurity) {
            require(file.sha256 != null && file.wrappedDataKey != null)
            require(chunks.size == file.chunkCount)
            chunks.forEach { chunk ->
                require(chunk.uploadStatus in setOf(ChunkUploadStatus.PENDING, ChunkUploadStatus.UPLOADED))
                require(chunk.plaintextSha256?.matches(SHA_256) == true)
                require(chunk.encryptedSizeBytes == plaintextSize(file, chunk.partIndex) + CryptoEngine.ENVELOPE_OVERHEAD_BYTES)
            }
        }
        UploadResumeState(
            file = UploadFileSnapshot(
                taskId = task.id,
                fileId = file.id,
                originalName = file.name,
                sourceUri = sourceUri,
                sizeBytes = file.sizeBytes,
                chunkSizeBytes = file.chunkSizeBytes,
                chunkCount = file.chunkCount,
                sha256 = file.sha256,
                wrappedDataKey = file.wrappedDataKey?.copyOf(),
            ),
            expectedChunks = chunks.map { chunk ->
                ExpectedUploadChunk(
                    partIndex = chunk.partIndex,
                    plaintextSha256 = requireNotNull(chunk.plaintextSha256),
                    plaintextSizeBytes = plaintextSize(file, chunk.partIndex),
                )
            },
            uploadedChunks = chunks.filter { it.uploadStatus == ChunkUploadStatus.UPLOADED }.map { chunk ->
                UploadedChunk(
                    partIndex = chunk.partIndex,
                    messageId = requireNotNull(chunk.messageId),
                    telegramFileId = requireNotNull(chunk.telegramFileId),
                    nonce = requireNotNull(chunk.nonce).copyOf(),
                    encryptedSizeBytes = chunk.encryptedSizeBytes,
                )
            },
        )
    }

    override suspend fun persistSecurityMetadata(
        taskId: String,
        sha256: String,
        wrappedDataKey: ByteArray,
        expectedChunks: List<ExpectedUploadChunk>,
    ) = database.withTransaction {
        require(sha256.matches(SHA_256))
        require(wrappedDataKey.size == CryptoEngine.DATA_KEY_BYTES + CryptoEngine.ENVELOPE_OVERHEAD_BYTES)
        val task = requireUploadTask(taskId)
        val file = requireNotNull(task.fileId?.let { database.fileDao().getById(it) })
        requireValidManifest(file, expectedChunks)
        if (file.sha256 != null || file.wrappedDataKey != null) {
            require(file.sha256 == sha256 && file.wrappedDataKey?.contentEquals(wrappedDataKey) == true)
            require(database.chunkDao().getForFile(file.id).matchesManifest(expectedChunks))
            return@withTransaction
        }
        require(database.chunkDao().getForFile(file.id).isEmpty())
        require(
            database.fileDao().persistUploadSecurity(
                id = file.id,
                sha256 = sha256,
                wrappedDataKey = wrappedDataKey.copyOf(),
                status = FileStatus.UPLOADING,
            ) == 1,
        )
        database.chunkDao().upsertAll(
            expectedChunks.map { chunk ->
                ChunkEntity(
                    id = deterministicChunkId(file.id, chunk.partIndex),
                    fileId = file.id,
                    partIndex = chunk.partIndex,
                    messageId = null,
                    telegramFileId = null,
                    nonce = null,
                    encryptedSizeBytes = chunk.plaintextSizeBytes + CryptoEngine.ENVELOPE_OVERHEAD_BYTES,
                    uploadStatus = ChunkUploadStatus.PENDING,
                    plaintextSha256 = chunk.plaintextSha256,
                )
            },
        )
    }

    override suspend fun markChunkSending(taskId: String, chunk: SendingUploadChunk) =
        database.withTransaction {
            require(chunk.partIndex >= 0 && chunk.nonce.size == NONCE_BYTES)
            val task = requireUploadTask(taskId)
            require(task.status == TransferStatus.RUNNING)
            val fileId = requireNotNull(task.fileId)
            val existing = database.chunkDao().getForFile(fileId)
                .single { it.partIndex == chunk.partIndex }
            require(
                existing.uploadStatus == ChunkUploadStatus.PENDING &&
                    existing.messageId == null &&
                    existing.telegramFileId == null &&
                    existing.nonce == null &&
                    existing.encryptedSizeBytes == chunk.encryptedSizeBytes
            )
            database.chunkDao().upsert(
                existing.copy(
                    nonce = chunk.nonce.copyOf(),
                    uploadStatus = ChunkUploadStatus.UPLOADING,
                ),
            )
        }

    override suspend fun discardSendingChunk(taskId: String, partIndex: Int) =
        database.withTransaction {
            val task = requireUploadTask(taskId)
            val fileId = requireNotNull(task.fileId)
            val existing = database.chunkDao().getForFile(fileId)
                .single { it.partIndex == partIndex }
            require(existing.uploadStatus == ChunkUploadStatus.UPLOADING)
            database.chunkDao().upsert(
                existing.copy(nonce = null, uploadStatus = ChunkUploadStatus.PENDING),
            )
        }

    override suspend fun recordUploadedChunk(taskId: String, chunk: UploadedChunk) =
        database.withTransaction {
            val task = requireUploadTask(taskId)
            val fileId = requireNotNull(task.fileId)
            val existing = database.chunkDao().getForFile(fileId)
                .single { it.partIndex == chunk.partIndex }
            if (existing.uploadStatus == ChunkUploadStatus.UPLOADED) {
                require(
                    existing.messageId == chunk.messageId &&
                        existing.telegramFileId == chunk.telegramFileId &&
                        existing.nonce?.contentEquals(chunk.nonce) == true &&
                        existing.encryptedSizeBytes == chunk.encryptedSizeBytes &&
                        existing.uploadStatus == ChunkUploadStatus.UPLOADED
                )
                return@withTransaction
            }
            require(
                existing.uploadStatus == ChunkUploadStatus.UPLOADING &&
                    existing.messageId == null &&
                    existing.telegramFileId == null &&
                    existing.nonce?.contentEquals(chunk.nonce) == true &&
                    existing.encryptedSizeBytes == chunk.encryptedSizeBytes
            )
            database.chunkDao().upsert(
                existing.copy(
                    messageId = chunk.messageId,
                    telegramFileId = chunk.telegramFileId,
                    uploadStatus = ChunkUploadStatus.UPLOADED,
                ),
            )
        }

    override suspend fun updateProgress(taskId: String, progress: UploadProgress) =
        database.withTransaction {
            val task = requireUploadTask(taskId)
            require(task.status == TransferStatus.RUNNING)
            require(progress.totalBytes == task.totalBytes && progress.totalChunks == task.totalChunks)
            require(progress.completedBytes >= task.completedBytes)
            require(progress.completedChunks >= task.currentChunk)
            require(
                database.transferTaskDao().updateProgress(
                    id = taskId,
                    completedBytes = progress.completedBytes,
                    currentChunk = progress.completedChunks,
                    speedBytesPerSecond = progress.speedBytesPerSecond,
                    updatedAt = clock(),
                ) == 1,
            )
        }

    override suspend fun publishAndFinalize(taskId: String, publish: suspend () -> Unit) =
        commitMutex.withLock {
            database.withTransaction {
                val task = requireUploadTask(taskId)
                require(task.status == TransferStatus.RUNNING)
            }
            publish()
            finalizeAfterIndex(taskId)
        }

    private suspend fun finalizeAfterIndex(taskId: String) = database.withTransaction {
        val task = requireUploadTask(taskId)
        require(task.status == TransferStatus.RUNNING)
        val file = requireNotNull(task.fileId?.let { database.fileDao().getById(it) })
        require(file.sha256 != null && file.wrappedDataKey != null)
        val chunks = database.chunkDao().getForFile(file.id)
        require(chunks.size == file.chunkCount && chunks.all { it.uploadStatus == ChunkUploadStatus.UPLOADED })
        val index = requireNotNull(database.indexStateDao().get(com.teledrive.lite.database.IndexStateEntity.SINGLETON_ID))
        require(
            index.revision > 0 &&
                index.currentIndexMessageId != null &&
                index.currentIndexFileId != null &&
                index.syncStatus in setOf(IndexSyncStatus.SYNCED, IndexSyncStatus.DIRTY)
        )
        val now = clock()
        require(database.fileDao().finalizeUpload(file.id, FileStatus.AVAILABLE, now) == 1)
        require(
            database.transferTaskDao().updateProgress(
                task.id,
                task.totalBytes,
                task.totalChunks,
                0,
                now,
            ) == 1,
        )
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
        val task = requireUploadTask(taskId)
        require(task.status in STARTABLE_TASK_STATES)
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

    suspend fun markStopped(taskId: String, status: TransferStatus, errorCode: String?) =
        database.withTransaction {
            val task = requireUploadTask(taskId)
            require(status in STOPPED_TASK_STATES)
            require(status != TransferStatus.FAILED || !errorCode.isNullOrBlank())
            if (task.status == TransferStatus.SUCCESS || task.status == TransferStatus.CANCELED) {
                return@withTransaction
            }
            require(
                database.transferTaskDao().updateStatus(
                    task.id,
                    status,
                    errorCode,
                    0,
                    clock(),
                ) == 1,
            )
        }

    suspend fun markRetry(taskId: String, retryAtEpochMillis: Long, errorCode: String) =
        database.withTransaction {
            val task = requireUploadTask(taskId)
            require(task.status == TransferStatus.RUNNING)
            require(retryAtEpochMillis > clock() && errorCode.isNotBlank())
            require(
                database.transferTaskDao().scheduleRetry(
                    task.id,
                    retryAtEpochMillis,
                    errorCode,
                    clock(),
                ) == 1,
            )
        }

    suspend fun retryDelayMillis(taskId: String): Long = database.withTransaction {
        val task = requireUploadTask(taskId)
        if (task.status != TransferStatus.WAITING_FOR_RETRY) return@withTransaction 0L
        (requireNotNull(task.nextRetryAtEpochMillis) - clock()).coerceAtLeast(0L)
    }

    suspend fun markWaitingForNetwork(taskId: String) = database.withTransaction {
        val task = requireUploadTask(taskId)
        require(task.status == TransferStatus.QUEUED)
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

    suspend fun markQueuedUploadsWaitingForNetwork(): Int = database.withTransaction {
        database.transferTaskDao().markQueuedUploadsWaitingForNetwork(clock())
    }

    /** Marks queued or running work canceled before asking WorkManager to stop it. */
    suspend fun cancel(taskId: String): String? = commitMutex.withLock {
        database.withTransaction {
            val task = requireUploadTask(taskId)
            if (task.status == TransferStatus.CANCELED) return@withTransaction task.workRequestId
            require(UploadRetryPolicy.canCancel(task))
            require(
                database.transferTaskDao().updateStatus(
                    task.id,
                    TransferStatus.CANCELED,
                    USER_CANCELED_ERROR,
                    0,
                    clock(),
                ) == 1,
            )
            task.workRequestId
        }
    }

    /** Requeues the same durable upload with a fresh WorkManager identity. */
    suspend fun prepareRetry(taskId: String, workRequestId: String): QueuedUpload =
        database.withTransaction {
            require(runCatching { UUID.fromString(workRequestId) }.isSuccess)
            val task = requireUploadTask(taskId)
            require(UploadRetryPolicy.canRetry(task))
            val fileId = requireNotNull(task.fileId)
            requireNotNull(task.sourceUri)
            val file = requireNotNull(database.fileDao().getById(fileId))
            require(file.status in UPLOAD_FILE_STATES && !file.isCloudIndexed)
            require(
                database.transferTaskDao().retryWithWorkRequest(
                    id = task.id,
                    workRequestId = workRequestId,
                    updatedAt = clock(),
                ) == 1,
            )
            QueuedUpload(task.id, fileId, workRequestId)
        }

    private suspend fun requireUploadTask(taskId: String): TransferTaskEntity =
        requireNotNull(database.transferTaskDao().getById(taskId)).also {
            require(it.type == TransferType.UPLOAD)
        }

    private fun deterministicChunkId(fileId: String, partIndex: Int): String =
        UUID.nameUUIDFromBytes("teledrive-chunk-v1:$fileId:$partIndex".encodeToByteArray()).toString()

    private fun requireValidManifest(file: FileEntity, chunks: List<ExpectedUploadChunk>) {
        require(chunks.size == file.chunkCount)
        chunks.sortedBy(ExpectedUploadChunk::partIndex).forEachIndexed { index, chunk ->
            require(
                chunk.partIndex == index &&
                    chunk.plaintextSha256.matches(SHA_256) &&
                    chunk.plaintextSizeBytes == plaintextSize(file, index)
            )
        }
    }

    private fun List<ChunkEntity>.matchesManifest(expected: List<ExpectedUploadChunk>): Boolean =
        sortedBy(ChunkEntity::partIndex).map { chunk ->
            ExpectedUploadChunk(
                chunk.partIndex,
                chunk.plaintextSha256.orEmpty(),
                chunk.encryptedSizeBytes - CryptoEngine.ENVELOPE_OVERHEAD_BYTES,
            )
        } == expected.sortedBy(ExpectedUploadChunk::partIndex)

    private fun plaintextSize(file: FileEntity, partIndex: Int): Long {
        require(partIndex in 0 until file.chunkCount)
        if (file.sizeBytes == 0L) return 0
        val offset = partIndex.toLong() * file.chunkSizeBytes
        return kotlin.math.min(file.chunkSizeBytes.toLong(), file.sizeBytes - offset)
    }

    private companion object {
        val UPLOAD_FILE_STATES = setOf(
            FileStatus.PENDING,
            FileStatus.ENCRYPTING,
            FileStatus.UPLOADING,
            FileStatus.FAILED,
        )
        val STARTABLE_TASK_STATES = setOf(
            TransferStatus.QUEUED,
            TransferStatus.PAUSED,
            TransferStatus.WAITING_FOR_NETWORK,
            TransferStatus.WAITING_FOR_RETRY,
            TransferStatus.FAILED,
            TransferStatus.RUNNING,
        )
        val STOPPED_TASK_STATES = setOf(
            TransferStatus.FAILED,
            TransferStatus.CANCELED,
            TransferStatus.WAITING_FOR_NETWORK,
            TransferStatus.WAITING_FOR_RETRY,
        )
        const val USER_CANCELED_ERROR = "USER_CANCELED"
        const val NONCE_BYTES = 12
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}
