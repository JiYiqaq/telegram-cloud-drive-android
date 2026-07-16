package com.teledrive.lite.repository

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudSnapshotValidatorTest {
    @Test
    fun acceptsACompleteAvailableFile() {
        CloudSnapshotValidator.requireValid(validSnapshot())
    }

    @Test
    fun acceptsAnEmptyRootSnapshot() {
        val valid = validSnapshot()
        CloudSnapshotValidator.requireValid(valid.copy(files = emptyList(), chunks = emptyList()))
    }

    @Test
    fun rejectsMissingGappedAndOutOfRangeChunks() {
        val valid = validSnapshot()
        listOf(
            valid.copy(chunks = emptyList()),
            valid.copy(files = valid.files.map { it.copy(chunkCount = 0) }, chunks = emptyList()),
            valid.copy(chunks = valid.chunks.map { it.copy(partIndex = 1) }),
            valid.copy(
                files = valid.files.map { it.copy(chunkCount = 2) },
                chunks = valid.chunks.map { it.copy(partIndex = 1) },
            ),
        ).forEach { snapshot ->
            val error = assertThrows(DriveRepositoryException::class.java) {
                CloudSnapshotValidator.requireValid(snapshot)
            }
            assertEquals(DriveRepositoryFailure.INVALID_CLOUD_SNAPSHOT, error.failure)
        }
    }

    @Test
    fun rejectsIncompleteRemoteMetadataAndCrossTypeCaseConflicts() {
        val valid = validSnapshot()
        val missingRemoteId = valid.copy(
            chunks = valid.chunks.map { it.copy(telegramFileId = null) },
        )
        val conflictingFolder = valid.copy(
            folders = valid.folders + FolderEntity("folder", "A.BIN", "root", 1, 1),
        )

        listOf(missingRemoteId, conflictingFolder).forEach { snapshot ->
            val error = assertThrows(DriveRepositoryException::class.java) {
                CloudSnapshotValidator.requireValid(snapshot)
            }
            assertEquals(DriveRepositoryFailure.INVALID_CLOUD_SNAPSHOT, error.failure)
        }
    }

    @Test
    fun nameConflictsMatchSqliteNoCaseSemantics() {
        val valid = validSnapshot()
        val unicodeCaseVariants = valid.copy(
            folders = valid.folders + FolderEntity("folder", "Ä", "root", 1, 1),
            files = valid.files.map { it.copy(name = "ä") },
        )

        CloudSnapshotValidator.requireValid(unicodeCaseVariants)

        val asciiCaseConflict = unicodeCaseVariants.copy(
            files = unicodeCaseVariants.files.map { it.copy(name = "Ä.txt") },
            folders = unicodeCaseVariants.folders.map {
                if (it.id == "folder") it.copy(name = "Ä.TXT") else it
            },
        )
        val error = assertThrows(DriveRepositoryException::class.java) {
            CloudSnapshotValidator.requireValid(asciiCaseConflict)
        }
        assertEquals(DriveRepositoryFailure.INVALID_CLOUD_SNAPSHOT, error.failure)
    }

    @Test
    fun deletionStateRequiresRecoverablePendingOperationAndRemainingIds() {
        val valid = validSnapshot()
        val deletionSnapshot = valid.copy(
            files = valid.files.map { it.copy(status = FileStatus.PARTIALLY_DELETED) },
            chunks = valid.chunks.map { it.copy(uploadStatus = ChunkUploadStatus.FAILED) },
            pendingOperations = listOf(
                PendingOperationEntity(
                    id = "delete:file",
                    type = PendingOperationType.DELETE,
                    targetId = "file",
                    payloadJson = null,
                    remainingMessageIdsJson = "[7]",
                    baseRevision = 1,
                    candidateRevision = null,
                    indexConfirmedAtEpochMillis = null,
                    status = PendingOperationStatus.FAILED,
                    attempt = 1,
                    nextRetryAtEpochMillis = null,
                    errorCode = "HTTP",
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 2,
                ),
            ),
        )
        CloudSnapshotValidator.requireValid(deletionSnapshot)

        val error = assertThrows(DriveRepositoryException::class.java) {
            CloudSnapshotValidator.requireValid(deletionSnapshot.copy(pendingOperations = emptyList()))
        }
        assertEquals(DriveRepositoryFailure.INVALID_CLOUD_SNAPSHOT, error.failure)
    }

    @Test
    fun nonDeletePendingOperationsMustReferenceSnapshotEntries() {
        val valid = validSnapshot()
        val invalid = valid.copy(
            pendingOperations = listOf(
                PendingOperationEntity(
                    id = "rename-missing",
                    type = PendingOperationType.RENAME,
                    targetId = "missing",
                    payloadJson = "{}",
                    remainingMessageIdsJson = null,
                    baseRevision = 1,
                    candidateRevision = null,
                    indexConfirmedAtEpochMillis = null,
                    status = PendingOperationStatus.PENDING,
                    attempt = 0,
                    nextRetryAtEpochMillis = null,
                    errorCode = null,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                ),
            ),
        )

        val error = assertThrows(DriveRepositoryException::class.java) {
            CloudSnapshotValidator.requireValid(invalid)
        }
        assertEquals(DriveRepositoryFailure.INVALID_CLOUD_SNAPSHOT, error.failure)
    }

    @Test
    fun indexRevisionAndMessageChainMustBeInternallyConsistent() {
        val valid = validSnapshot()
        val invalidStates = listOf(
            valid.indexState.copy(revision = 0),
            valid.indexState.copy(previousIndexMessageId = 7),
            valid.indexState.copy(revision = 2, previousIndexMessageId = null),
            valid.indexState.copy(currentIndexMessageId = 0),
            valid.indexState.copy(
                revision = 2,
                previousIndexMessageId = valid.indexState.currentIndexMessageId,
            ),
        )

        invalidStates.forEach { indexState ->
            val error = assertThrows(DriveRepositoryException::class.java) {
                CloudSnapshotValidator.requireValid(valid.copy(indexState = indexState))
            }
            assertEquals(DriveRepositoryFailure.INVALID_CLOUD_SNAPSHOT, error.failure)
        }
    }

    @Test
    fun folderDeletionTombstoneReferencesAnExistingFormerParent() {
        val valid = validSnapshot()
        val operation = PendingOperationEntity(
            id = "00000000-0000-0000-0000-000000000099",
            type = PendingOperationType.DELETE_FOLDER,
            targetId = "00000000-0000-0000-0000-000000000098",
            payloadJson = "{\"parentId\":\"root\"}",
            remainingMessageIdsJson = null,
            baseRevision = 1,
            candidateRevision = null,
            indexConfirmedAtEpochMillis = null,
            status = PendingOperationStatus.PENDING,
            attempt = 0,
            nextRetryAtEpochMillis = null,
            errorCode = null,
            createdAtEpochMillis = 2,
            updatedAtEpochMillis = 2,
        )

        CloudSnapshotValidator.requireValid(valid.copy(pendingOperations = listOf(operation)))
        assertThrows(DriveRepositoryException::class.java) {
            CloudSnapshotValidator.requireValid(
                valid.copy(
                    pendingOperations = listOf(
                        operation.copy(payloadJson = "{\"parentId\":\"missing\"}"),
                    ),
                ),
            )
        }
    }

    private fun validSnapshot(): CloudCacheSnapshot = CloudCacheSnapshot(
        folders = listOf(FolderEntity("root", "我的云盘", null, 1, 1)),
        files = listOf(
            FileEntity(
                id = "file",
                name = "a.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 1,
                createdAtEpochMillis = 1,
                modifiedAtEpochMillis = 1,
                uploadedAtEpochMillis = 2,
                parentFolderId = "root",
                sha256 = "ab".repeat(32),
                encryptionFormatVersion = 1,
                chunkSizeBytes = 1024,
                chunkCount = 1,
                wrappedDataKey = byteArrayOf(1),
                status = FileStatus.AVAILABLE,
                isCloudIndexed = true,
            ),
        ),
        chunks = listOf(
            ChunkEntity(
                id = "chunk",
                fileId = "file",
                partIndex = 0,
                messageId = 7,
                telegramFileId = "remote",
                nonce = ByteArray(12),
                encryptedSizeBytes = 17,
                uploadStatus = ChunkUploadStatus.UPLOADED,
            ),
        ),
        indexState = IndexStateEntity(
            id = IndexStateEntity.SINGLETON_ID,
            formatVersion = 1,
            revision = 1,
            rootFolderId = "root",
            currentIndexMessageId = 8,
            previousIndexMessageId = null,
            currentIndexFileId = "index-file",
            lastSyncedAtEpochMillis = 2,
            syncStatus = IndexSyncStatus.SYNCED,
        ),
    )
}
