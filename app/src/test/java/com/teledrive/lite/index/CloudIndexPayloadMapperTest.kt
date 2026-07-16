package com.teledrive.lite.index

import com.teledrive.lite.crypto.KeyDerivationParameters
import com.teledrive.lite.database.ChunkEntity
import com.teledrive.lite.database.FileEntity
import com.teledrive.lite.database.FolderEntity
import com.teledrive.lite.database.IndexStateEntity
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.repository.CloudCacheSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudIndexPayloadMapperTest {
    @Test
    fun mapsValidatedRoomSnapshotToCanonicalPayloadAndBack() {
        val snapshot = snapshot()
        val parameters = KeyDerivationParameters.pbkdf2(ByteArray(16) { it.toByte() }, 600_000)

        val payload = CloudIndexPayloadMapper.toPayload(
            snapshot = snapshot,
            appVersion = "0.1.0",
            revision = 1,
            currentIndexMessageId = 71,
            previous = null,
            createdAtEpochMillis = 1_000,
            updatedAtEpochMillis = 2_000,
            keyDerivation = parameters,
        )
        val decoded = IndexCodec.decode(IndexCodec.encode(payload))
        val restored = CloudIndexPayloadMapper.toCloudCacheSnapshot(
            payload = decoded,
            currentIndexFileId = "index-file",
            syncedAtEpochMillis = 3_000,
        )

        assertEquals(1L, restored.indexState.revision)
        assertEquals(71L, restored.indexState.currentIndexMessageId)
        assertEquals("index-file", restored.indexState.currentIndexFileId)
        assertEquals(IndexSyncStatus.SYNCED, restored.indexState.syncStatus)
        assertEquals(snapshot.folders, restored.folders)
        assertEquals(snapshot.files.single().copy(wrappedDataKey = null), restored.files.single().copy(wrappedDataKey = null))
        assertArrayEquals(snapshot.files.single().wrappedDataKey, restored.files.single().wrappedDataKey)
        assertEquals(snapshot.chunks.single().copy(nonce = null), restored.chunks.single().copy(nonce = null))
        assertArrayEquals(snapshot.chunks.single().nonce, restored.chunks.single().nonce)
    }

    @Test
    fun completeUploadCandidateIsPublishedButOtherQueuedLocalFilesStayOutOfCloudIndex() {
        val original = snapshot()
        val uploading = original.files.single().copy(
            uploadedAtEpochMillis = null,
            status = FileStatus.UPLOADING,
            isCloudIndexed = false,
        )
        val queued = uploading.copy(
            id = "00000000-0000-0000-0000-000000000003",
            name = "queued.txt",
            sha256 = null,
            wrappedDataKey = null,
            status = FileStatus.PENDING,
        )
        val payload = CloudIndexPayloadMapper.toPayload(
            snapshot = original.copy(files = listOf(uploading, queued)),
            appVersion = "0.1.0",
            revision = 1,
            currentIndexMessageId = 71,
            previous = null,
            createdAtEpochMillis = 1_000,
            updatedAtEpochMillis = 2_000,
            keyDerivation = KeyDerivationParameters.pbkdf2(ByteArray(16), 1),
        )

        assertEquals(listOf(uploading.id), payload.files.map { it.id })
        assertEquals(IndexFileStatus.AVAILABLE, payload.files.single().status)
        assertEquals(true, payload.files.single().isCloudIndexed)
        assertEquals(listOf(uploading.id), payload.chunks.map { it.fileId }.distinct())
    }

    private fun snapshot(): CloudCacheSnapshot {
        val folder = FolderEntity("root", "TeleDrive", null, 1_000, 1_000)
        val file = FileEntity(
            id = "00000000-0000-0000-0000-000000000001",
            name = "hello.txt",
            mimeType = "text/plain",
            sizeBytes = 5,
            createdAtEpochMillis = 1_100,
            modifiedAtEpochMillis = 1_200,
            uploadedAtEpochMillis = 1_300,
            parentFolderId = "root",
            sha256 = "ab".repeat(32),
            encryptionFormatVersion = 1,
            chunkSizeBytes = 18 * 1024 * 1024,
            chunkCount = 1,
            wrappedDataKey = ByteArray(66) { it.toByte() },
            status = FileStatus.AVAILABLE,
            isCloudIndexed = true,
        )
        val chunk = ChunkEntity(
            id = "00000000-0000-0000-0000-000000000002",
            fileId = file.id,
            partIndex = 0,
            messageId = 60,
            telegramFileId = "chunk-file",
            nonce = ByteArray(12) { (it + 1).toByte() },
            encryptedSizeBytes = 39,
            uploadStatus = ChunkUploadStatus.UPLOADED,
        )
        return CloudCacheSnapshot(
            folders = listOf(folder),
            files = listOf(file),
            chunks = listOf(chunk),
            indexState = IndexStateEntity(
                id = IndexStateEntity.SINGLETON_ID,
                formatVersion = 1,
                revision = 0,
                rootFolderId = "root",
                currentIndexMessageId = null,
                previousIndexMessageId = null,
                currentIndexFileId = null,
                lastSyncedAtEpochMillis = null,
                syncStatus = IndexSyncStatus.DIRTY,
            ),
        )
    }
}
