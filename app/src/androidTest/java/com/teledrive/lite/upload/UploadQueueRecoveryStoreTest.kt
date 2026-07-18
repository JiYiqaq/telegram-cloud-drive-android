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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadQueueRecoveryStoreTest {
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
    fun recoveryReassignsOnlyNonTerminalUploadWork() = runBlocking {
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, 1, 1))
        val ids = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000101",
                "00000000-0000-0000-0000-000000000102",
            ),
        )
        val queued = UploadQueueRepository(
            database,
            idGenerator = { ids.removeFirst() },
            clock = { 10 },
        ).enqueue(
            UploadSelection(
                sourceUri = "content://provider/stuck",
                displayName = "stuck.txt",
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
        val replacementWorkId = UUID.randomUUID().toString()

        assertEquals(listOf(queued.taskId), store.recoverableTaskIds())
        assertTrue(store.replacePendingWorkRequest(queued.taskId, replacementWorkId))
        assertEquals(
            replacementWorkId,
            database.transferTaskDao().getById(queued.taskId)?.workRequestId,
        )
        assertEquals(
            TransferStatus.QUEUED,
            database.transferTaskDao().getById(queued.taskId)?.status,
        )

        store.cancel(queued.taskId)

        assertEquals(emptyList<String>(), store.recoverableTaskIds())
        assertFalse(
            store.replacePendingWorkRequest(queued.taskId, UUID.randomUUID().toString()),
        )
    }
}
