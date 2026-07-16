package com.teledrive.lite.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teledrive.lite.database.FolderEntity
import com.teledrive.lite.database.IndexStateEntity
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.util.Sha256
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomUploadStoreTest {
    private lateinit var database: TeleDriveDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TeleDriveDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun queuePersistsSourceAndFinalizesOnlyWithPinnedIndexAndCompleteChunks() = runBlocking {
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, 1, 1))
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
            ),
        )
        val queue = UploadQueueRepository(database, idGenerator = { ids.removeFirst() }, clock = { 10 })
        val queued = queue.enqueue(
            UploadSelection(
                sourceUri = "content://provider/file",
                displayName = "hello.txt",
                mimeType = "text/plain",
                sizeBytes = 3,
                createdAtEpochMillis = 1,
                modifiedAtEpochMillis = 2,
                parentFolderId = "root",
                chunkSizeBytes = 4,
            ),
            UUID.randomUUID().toString(),
        )
        val store = RoomUploadStore(database, clock = { 20 })

        store.markRunning(queued.taskId)
        val expected = ExpectedUploadChunk(0, Sha256.digest(byteArrayOf(1, 2, 3)), 3)
        store.persistSecurityMetadata(
            queued.taskId,
            "ab".repeat(32),
            ByteArray(66),
            listOf(expected),
        )
        store.markChunkSending(queued.taskId, SendingUploadChunk(0, ByteArray(12), 37))
        store.recordUploadedChunk(
            queued.taskId,
            UploadedChunk(0, 7, "remote", ByteArray(12), 37),
        )
        store.updateProgress(queued.taskId, UploadProgress(3, 3, 1, 1, 100))
        database.indexStateDao().upsert(
            IndexStateEntity(
                IndexStateEntity.SINGLETON_ID,
                1,
                1,
                "root",
                8,
                null,
                "index-file",
                19,
                IndexSyncStatus.SYNCED,
            ),
        )

        store.publishAndFinalize(queued.taskId) {}

        val file = database.fileDao().getById(queued.fileId)
        val task = database.transferTaskDao().getById(queued.taskId)
        assertNotNull(file)
        assertEquals(FileStatus.AVAILABLE, file?.status)
        assertEquals(true, file?.isCloudIndexed)
        assertEquals(TransferStatus.SUCCESS, task?.status)
        assertEquals("content://provider/file", task?.sourceUri)
    }

    @Test
    fun cancelAndRetryUseANewWorkRequestWhileKeepingConfirmedChunks() = runBlocking {
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, 1, 1))
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000011",
                "00000000-0000-0000-0000-000000000012",
            ),
        )
        val originalWorkId = UUID.randomUUID().toString()
        val queued = UploadQueueRepository(
            database,
            idGenerator = { ids.removeFirst() },
            clock = { 10 },
        ).enqueue(
            UploadSelection(
                sourceUri = "content://provider/resumable",
                displayName = "resume.txt",
                mimeType = "text/plain",
                sizeBytes = 3,
                createdAtEpochMillis = 1,
                modifiedAtEpochMillis = 2,
                parentFolderId = "root",
                chunkSizeBytes = 4,
            ),
            originalWorkId,
        )
        val store = RoomUploadStore(database, clock = { 20 })
        store.markRunning(queued.taskId)
        val expected = ExpectedUploadChunk(0, Sha256.digest(byteArrayOf(1, 2, 3)), 3)
        store.persistSecurityMetadata(
            queued.taskId,
            "cd".repeat(32),
            ByteArray(66),
            listOf(expected),
        )
        store.markChunkSending(queued.taskId, SendingUploadChunk(0, ByteArray(12), 37))
        store.recordUploadedChunk(
            queued.taskId,
            UploadedChunk(0, 17, "confirmed-remote", ByteArray(12), 37),
        )
        store.updateProgress(queued.taskId, UploadProgress(3, 3, 1, 1, 100))

        assertEquals(originalWorkId, store.cancel(queued.taskId))
        assertEquals(
            TransferStatus.CANCELED,
            database.transferTaskDao().getById(queued.taskId)?.status,
        )

        val replacementWorkId = UUID.randomUUID().toString()
        val retried = store.prepareRetry(queued.taskId, replacementWorkId)
        val resumed = store.load(queued.taskId)
        val task = database.transferTaskDao().getById(queued.taskId)

        assertEquals(replacementWorkId, retried.workRequestId)
        assertEquals(replacementWorkId, task?.workRequestId)
        assertEquals(TransferStatus.QUEUED, task?.status)
        assertEquals(1, task?.attempt)
        assertEquals("confirmed-remote", resumed.uploadedChunks.single().telegramFileId)
    }

    @Test
    fun uploadWithUnknownNetworkResultCannotBeBlindlyRetried() = runBlocking {
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, 1, 1))
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000021",
                "00000000-0000-0000-0000-000000000022",
            ),
        )
        val queued = UploadQueueRepository(
            database,
            idGenerator = { ids.removeFirst() },
            clock = { 10 },
        ).enqueue(
            UploadSelection(
                sourceUri = "content://provider/ambiguous",
                displayName = "ambiguous.txt",
                mimeType = "text/plain",
                sizeBytes = 3,
                createdAtEpochMillis = 1,
                modifiedAtEpochMillis = 2,
                parentFolderId = "root",
                chunkSizeBytes = 4,
            ),
            UUID.randomUUID().toString(),
        )
        val store = RoomUploadStore(database, clock = { 20 })
        store.markRunning(queued.taskId)
        store.markStopped(
            queued.taskId,
            TransferStatus.FAILED,
            UploadRetryPolicy.RESULT_UNKNOWN_ERROR,
        )

        val result = runCatching {
            store.prepareRetry(queued.taskId, UUID.randomUUID().toString())
        }

        assertEquals(true, result.isFailure)
        assertEquals(
            TransferStatus.FAILED,
            database.transferTaskDao().getById(queued.taskId)?.status,
        )
    }

    @Test
    fun persistedSendingMarkerBlocksResumeAfterProcessDeathWindow() = runBlocking {
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, 1, 1))
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000031",
                "00000000-0000-0000-0000-000000000032",
            ),
        )
        val queued = UploadQueueRepository(
            database,
            idGenerator = { ids.removeFirst() },
            clock = { 10 },
        ).enqueue(
            UploadSelection(
                sourceUri = "content://provider/in-flight",
                displayName = "in-flight.txt",
                mimeType = "text/plain",
                sizeBytes = 3,
                createdAtEpochMillis = 1,
                modifiedAtEpochMillis = 2,
                parentFolderId = "root",
                chunkSizeBytes = 4,
            ),
            UUID.randomUUID().toString(),
        )
        val store = RoomUploadStore(database, clock = { 20 })
        store.markRunning(queued.taskId)
        store.persistSecurityMetadata(
            queued.taskId,
            "ef".repeat(32),
            ByteArray(66),
            listOf(ExpectedUploadChunk(0, Sha256.digest(byteArrayOf(1, 2, 3)), 3)),
        )
        store.markChunkSending(queued.taskId, SendingUploadChunk(0, ByteArray(12), 37))

        val result = runCatching { store.load(queued.taskId) }

        assertTrue(result.isFailure)
        assertEquals(
            UploadFailure.REMOTE_RESULT_UNKNOWN,
            (result.exceptionOrNull() as UploadException).failure,
        )
    }

    @Test
    fun cancellationCannotInterleaveWithIndexPublicationAndFinalization() = runBlocking {
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, 1, 1))
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000041",
                "00000000-0000-0000-0000-000000000042",
            ),
        )
        val queued = UploadQueueRepository(
            database,
            idGenerator = { ids.removeFirst() },
            clock = { 10 },
        ).enqueue(
            UploadSelection(
                sourceUri = "content://provider/commit",
                displayName = "commit.txt",
                mimeType = "text/plain",
                sizeBytes = 3,
                createdAtEpochMillis = 1,
                modifiedAtEpochMillis = 2,
                parentFolderId = "root",
                chunkSizeBytes = 4,
            ),
            UUID.randomUUID().toString(),
        )
        val store = RoomUploadStore(database, clock = { 20 })
        store.markRunning(queued.taskId)
        store.persistSecurityMetadata(
            queued.taskId,
            "12".repeat(32),
            ByteArray(66),
            listOf(ExpectedUploadChunk(0, Sha256.digest(byteArrayOf(1, 2, 3)), 3)),
        )
        store.markChunkSending(queued.taskId, SendingUploadChunk(0, ByteArray(12), 37))
        store.recordUploadedChunk(queued.taskId, UploadedChunk(0, 47, "remote", ByteArray(12), 37))
        store.updateProgress(queued.taskId, UploadProgress(3, 3, 1, 1, 100))
        database.indexStateDao().upsert(
            IndexStateEntity(
                IndexStateEntity.SINGLETON_ID,
                1,
                1,
                "root",
                48,
                null,
                "index-file",
                19,
                IndexSyncStatus.SYNCED,
            ),
        )
        val publicationEntered = CompletableDeferred<Unit>()
        val allowPublication = CompletableDeferred<Unit>()
        val publication = async {
            store.publishAndFinalize(queued.taskId) {
                publicationEntered.complete(Unit)
                allowPublication.await()
            }
        }
        publicationEntered.await()
        val cancellation = async {
            runCatching { store.cancel(queued.taskId) }
        }
        yield()
        assertFalse(cancellation.isCompleted)

        allowPublication.complete(Unit)
        publication.await()
        val cancellationResult = cancellation.await()

        assertTrue(cancellationResult.isFailure)
        assertEquals(
            TransferStatus.SUCCESS,
            database.transferTaskDao().getById(queued.taskId)?.status,
        )
    }
}
