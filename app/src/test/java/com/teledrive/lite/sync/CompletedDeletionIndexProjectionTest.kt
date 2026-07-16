package com.teledrive.lite.sync

import com.teledrive.lite.database.ChunkEntity
import com.teledrive.lite.database.FileEntity
import com.teledrive.lite.database.FolderEntity
import com.teledrive.lite.database.IndexStateEntity
import com.teledrive.lite.database.PendingOperationEntity
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.PendingOperationStatus
import com.teledrive.lite.model.PendingOperationType
import com.teledrive.lite.repository.CloudCacheSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedDeletionIndexProjectionTest {
    @Test
    fun removesOnlyFullyDeletedFileChunksAndOperationFromCandidate() {
        val snapshot = snapshot(
            fileStatus = FileStatus.DELETING,
            chunkStatus = ChunkUploadStatus.DELETED,
            remainingMessageIds = "[]",
        )

        val projected = CompletedDeletionIndexProjection.project(snapshot)

        assertTrue(projected.files.isEmpty())
        assertTrue(projected.chunks.isEmpty())
        assertTrue(projected.pendingOperations.isEmpty())
        assertEquals(snapshot.folders, projected.folders)
        assertEquals(snapshot.indexState, projected.indexState)
    }

    @Test
    fun retainsPartialDeletionRecoveryMetadata() {
        val snapshot = snapshot(
            fileStatus = FileStatus.PARTIALLY_DELETED,
            chunkStatus = ChunkUploadStatus.FAILED,
            remainingMessageIds = "[51]",
        )

        assertEquals(snapshot, CompletedDeletionIndexProjection.project(snapshot))
    }

    private fun snapshot(
        fileStatus: FileStatus,
        chunkStatus: ChunkUploadStatus,
        remainingMessageIds: String,
    ): CloudCacheSnapshot {
        val file = FileEntity(
            id = FILE_ID,
            name = "example.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 4,
            createdAtEpochMillis = 1,
            modifiedAtEpochMillis = 2,
            uploadedAtEpochMillis = 3,
            parentFolderId = "root",
            sha256 = "ab".repeat(32),
            encryptionFormatVersion = 1,
            chunkSizeBytes = 4,
            chunkCount = 1,
            wrappedDataKey = byteArrayOf(1),
            status = fileStatus,
            isCloudIndexed = true,
        )
        return CloudCacheSnapshot(
            folders = listOf(FolderEntity("root", "我的云盘", null, 1, 1)),
            files = listOf(file),
            chunks = listOf(
                ChunkEntity(
                    id = "chunk",
                    fileId = FILE_ID,
                    partIndex = 0,
                    messageId = 51,
                    telegramFileId = "remote",
                    nonce = ByteArray(12),
                    encryptedSizeBytes = 32,
                    uploadStatus = chunkStatus,
                ),
            ),
            indexState = IndexStateEntity(
                id = IndexStateEntity.SINGLETON_ID,
                formatVersion = 1,
                revision = 4,
                rootFolderId = "root",
                currentIndexMessageId = 90,
                previousIndexMessageId = 89,
                currentIndexFileId = "index-4",
                lastSyncedAtEpochMillis = 4,
                syncStatus = IndexSyncStatus.DIRTY,
            ),
            pendingOperations = listOf(
                PendingOperationEntity(
                    id = "delete:$FILE_ID",
                    type = PendingOperationType.DELETE,
                    targetId = FILE_ID,
                    payloadJson = null,
                    remainingMessageIdsJson = remainingMessageIds,
                    baseRevision = 4,
                    candidateRevision = null,
                    indexConfirmedAtEpochMillis = null,
                    status = if (remainingMessageIds == "[]") {
                        PendingOperationStatus.PENDING
                    } else {
                        PendingOperationStatus.FAILED
                    },
                    attempt = 0,
                    nextRetryAtEpochMillis = null,
                    errorCode = null,
                    createdAtEpochMillis = 4,
                    updatedAtEpochMillis = 4,
                ),
            ),
        )
    }

    private companion object {
        const val FILE_ID = "00000000-0000-0000-0000-000000000001"
    }
}
