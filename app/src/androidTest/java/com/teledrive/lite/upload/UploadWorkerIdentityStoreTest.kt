package com.teledrive.lite.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teledrive.lite.database.FolderEntity
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.model.TransferStatus
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadWorkerIdentityStoreTest {
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
    fun staleOrTerminalWorkerCannotLoadOrStartUpload() = runBlocking {
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, 1, 1))
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000201",
                "00000000-0000-0000-0000-000000000202",
            ),
        )
        val originalWorkId = UUID.randomUUID().toString()
        val queued = UploadQueueRepository(
            database,
            idGenerator = { ids.removeFirst() },
            clock = { 10 },
        ).enqueue(
            UploadSelection(
                sourceUri = "content://provider/identity",
                displayName = "identity.txt",
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
        val replacementWorkId = UUID.randomUUID().toString()
        assertTrue(store.replacePendingWorkRequest(queued.taskId, replacementWorkId))

        assertNull(store.loadForWorker(queued.taskId, originalWorkId))
        assertFalse(store.markRunning(queued.taskId, originalWorkId))
        assertNotNull(store.loadForWorker(queued.taskId, replacementWorkId))
        assertTrue(store.markRunning(queued.taskId, replacementWorkId))
        assertTrue(
            store.markStoppedForWork(
                queued.taskId,
                replacementWorkId,
                TransferStatus.FAILED,
                "UPLOAD_FAILED",
            ),
        )

        assertNull(store.loadForWorker(queued.taskId, replacementWorkId))
        assertFalse(store.markRunning(queued.taskId, replacementWorkId))
    }
}
