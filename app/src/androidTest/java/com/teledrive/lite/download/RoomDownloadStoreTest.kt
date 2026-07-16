package com.teledrive.lite.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teledrive.lite.database.ChunkEntity
import com.teledrive.lite.database.FileEntity
import com.teledrive.lite.database.FolderEntity
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.util.Sha256
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDownloadStoreTest {
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
    fun queuePersistsDestinationAndFinalizesOnlyAfterOutputCommit() = runBlocking {
        insertCloudFile(FileStatus.AVAILABLE)
        val workId = UUID.randomUUID().toString()
        val queued = DownloadQueueRepository(database, idGenerator = { "task" }, clock = { 10 })
            .enqueue(FILE_ID, "content://provider/output", workId)
        val store = RoomDownloadStore(database, clock = { 20 })

        val queuedTask = database.transferTaskDao().getById(queued.taskId)
        assertEquals("content://provider/output", queuedTask?.destinationUri)
        assertEquals(FileStatus.AVAILABLE, queuedTask?.previousFileStatus)
        assertEquals(FileStatus.DOWNLOADING, database.fileDao().getById(FILE_ID)?.status)

        store.markRunning(queued.taskId)
        val snapshot = store.load(queued.taskId)
        assertEquals("remote-0", snapshot.chunks.single().telegramFileId)
        store.updateProgress(queued.taskId, DownloadProgress(4, 4, 1, 1, 100))
        store.finalizeAfterDestination(queued.taskId)

        assertEquals(FileStatus.AVAILABLE, database.fileDao().getById(FILE_ID)?.status)
        assertEquals(TransferStatus.SUCCESS, database.transferTaskDao().getById("task")?.status)
    }

    @Test
    fun authenticationFailureMarksFileCorruptedAndCannotBeBlindlyRetried() = runBlocking {
        insertCloudFile(FileStatus.AVAILABLE)
        val queued = DownloadQueueRepository(database, idGenerator = { "task" }, clock = { 10 })
            .enqueue(FILE_ID, "content://provider/output", UUID.randomUUID().toString())
        val store = RoomDownloadStore(database, clock = { 20 })
        store.markRunning(queued.taskId)

        store.markFailed(queued.taskId, DownloadRetryPolicy.AUTHENTICATION_ERROR, corrupted = true)

        val task = database.transferTaskDao().getById(queued.taskId)!!
        assertEquals(FileStatus.CORRUPTED, database.fileDao().getById(FILE_ID)?.status)
        assertEquals(TransferStatus.FAILED, task.status)
        assertFalse(DownloadRetryPolicy.canRetry(task))
    }

    @Test
    fun cancellationRestoresPreviousStatusAndRetryUsesFreshWorkIdentity() = runBlocking {
        insertCloudFile(FileStatus.CORRUPTED)
        val firstWorkId = UUID.randomUUID().toString()
        val queued = DownloadQueueRepository(database, idGenerator = { "task" }, clock = { 10 })
            .enqueue(FILE_ID, "content://provider/output", firstWorkId)
        val store = RoomDownloadStore(database, clock = { 20 })
        store.markRunning(queued.taskId)
        store.updateProgress(queued.taskId, DownloadProgress(4, 4, 1, 1, 100))

        assertEquals(firstWorkId, store.cancel(queued.taskId))
        assertEquals(FileStatus.CORRUPTED, database.fileDao().getById(FILE_ID)?.status)

        val nextWorkId = UUID.randomUUID().toString()
        store.prepareRetry(queued.taskId, nextWorkId)
        val task = database.transferTaskDao().getById(queued.taskId)

        assertEquals(TransferStatus.QUEUED, task?.status)
        assertEquals(nextWorkId, task?.workRequestId)
        assertEquals(0L, task?.completedBytes)
        assertEquals(FileStatus.DOWNLOADING, database.fileDao().getById(FILE_ID)?.status)
    }

    @Test
    fun rateLimitPersistsExactRetryDeadlineAndDelay() = runBlocking {
        insertCloudFile(FileStatus.AVAILABLE)
        val queued = DownloadQueueRepository(database, idGenerator = { "task" }, clock = { 10 })
            .enqueue(FILE_ID, "content://provider/output", UUID.randomUUID().toString())
        val store = RoomDownloadStore(database, clock = { 1_000 })
        store.markRunning(queued.taskId)

        store.markRetry(queued.taskId, retryAtEpochMillis = 3_500, errorCode = "RATE_LIMITED")

        val task = database.transferTaskDao().getById(queued.taskId)
        assertEquals(TransferStatus.WAITING_FOR_RETRY, task?.status)
        assertEquals(3_500L, task?.nextRetryAtEpochMillis)
        assertEquals(2_500L, store.retryDelayMillis(queued.taskId))
    }

    private suspend fun insertCloudFile(status: FileStatus) {
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, 1, 1))
        database.fileDao().upsert(
            FileEntity(
                id = FILE_ID,
                name = "example.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 4,
                createdAtEpochMillis = 1,
                modifiedAtEpochMillis = 2,
                uploadedAtEpochMillis = 3,
                parentFolderId = "root",
                sha256 = Sha256.digest(byteArrayOf(1, 2, 3, 4)),
                encryptionFormatVersion = 1,
                chunkSizeBytes = 4,
                chunkCount = 1,
                wrappedDataKey = ByteArray(66),
                status = status,
                isCloudIndexed = true,
            ),
        )
        database.chunkDao().upsert(
            ChunkEntity(
                id = "chunk",
                fileId = FILE_ID,
                partIndex = 0,
                messageId = 11,
                telegramFileId = "remote-0",
                nonce = ByteArray(12),
                encryptedSizeBytes = 38,
                uploadStatus = ChunkUploadStatus.UPLOADED,
            ),
        )
    }

    private companion object {
        const val FILE_ID = "00000000-0000-0000-0000-000000000001"
    }
}
