package com.teledrive.lite.deletion

import androidx.room.withTransaction
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType

class RoomOrphanCleanupStore(
    private val database: TeleDriveDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : SafeDeletionStore {
    override suspend fun pendingChunks(fileId: String): List<PendingChunkDeletion> =
        database.withTransaction {
            val task = requireNotNull(database.transferTaskDao().getById(fileId))
            require(task.type == TransferType.UPLOAD && task.status in CLEANABLE_TASK_STATES)
            val uploadFile = requireNotNull(task.fileId?.let { database.fileDao().getById(it) })
            require(!uploadFile.isCloudIndexed)
            database.chunkDao().getForFile(uploadFile.id)
                .filter { chunk ->
                    chunk.messageId != null &&
                        chunk.uploadStatus in setOf(
                            ChunkUploadStatus.UPLOADED,
                            ChunkUploadStatus.FAILED,
                        )
                }
                .map { chunk -> PendingChunkDeletion(chunk.id, requireNotNull(chunk.messageId)) }
        }

    override suspend fun recordResult(
        fileId: String,
        chunkId: String,
        deleted: Boolean,
        errorCode: String?,
    ) = database.withTransaction {
        val task = requireNotNull(database.transferTaskDao().getById(fileId))
        require(task.type == TransferType.UPLOAD && task.status in CLEANABLE_TASK_STATES)
        val uploadFileId = requireNotNull(task.fileId)
        val chunk = requireNotNull(database.chunkDao().getById(chunkId))
        require(chunk.fileId == uploadFileId && chunk.messageId != null)
        if (!deleted) require(!errorCode.isNullOrBlank())
        require(
            database.chunkDao().updateStatus(
                chunkId,
                if (deleted) ChunkUploadStatus.DELETED else ChunkUploadStatus.FAILED,
            ) == 1,
        )
        if (!deleted) {
            database.fileDao().updateStatus(uploadFileId, FileStatus.FAILED, clock())
        }
    }

    suspend fun finalize(taskId: String) = database.withTransaction {
        val task = requireNotNull(database.transferTaskDao().getById(taskId))
        require(task.type == TransferType.UPLOAD && task.status in CLEANABLE_TASK_STATES)
        val uploadFile = requireNotNull(task.fileId?.let { database.fileDao().getById(it) })
        require(!uploadFile.isCloudIndexed)
        val chunks = database.chunkDao().getForFile(uploadFile.id)
        require(chunks.none { it.uploadStatus == ChunkUploadStatus.UPLOADING })
        require(
            chunks.filter { it.messageId != null }
                .all { it.uploadStatus == ChunkUploadStatus.DELETED },
        )
        require(database.fileDao().deleteById(uploadFile.id) == 1)
    }

    private companion object {
        val CLEANABLE_TASK_STATES = setOf(TransferStatus.CANCELED, TransferStatus.FAILED)
    }
}

class LocalOrphanCleanupPublisher(
    private val store: RoomOrphanCleanupStore,
) : DeletionIndexPublisher {
    override suspend fun publishPartial(fileId: String) = Unit

    override suspend fun publishRemoval(fileId: String) {
        store.finalize(fileId)
    }
}
