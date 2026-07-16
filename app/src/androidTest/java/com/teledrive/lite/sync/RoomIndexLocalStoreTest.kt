package com.teledrive.lite.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teledrive.lite.database.IndexStateEntity
import com.teledrive.lite.database.PendingOperationEntity
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.PendingOperationStatus
import com.teledrive.lite.model.PendingOperationType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomIndexLocalStoreTest {
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
    fun confirmedCommitConsumesOnlyFrozenMutationsAndLeavesNewerWorkDirty() = runBlocking {
        database.indexStateDao().upsert(
            IndexStateEntity(
                IndexStateEntity.SINGLETON_ID,
                1,
                1,
                "root",
                10,
                null,
                "old-file",
                900,
                IndexSyncStatus.DIRTY,
            ),
        )
        database.pendingOperationDao().upsert(operation("old-op", 1_000))
        val store = RoomIndexLocalStore(database, clock = { 2_000 })
        val prepared = store.beginJournal("journal", StableIndexState(1, 10, "old-file"))
        assertEquals(setOf("old-op"), prepared.includedOperationIds)

        database.pendingOperationDao().upsert(operation("new-op", 1_100))
        val document = RemoteIndexDocument(20, "new-file", IndexAtomicUpdater.INDEX_FILE_NAME, 100)
        val confirmed = prepared.copy(
            phase = IndexUpdatePhase.CONFIRMED,
            provisionalMessageId = 20,
            finalDocument = document,
        )
        store.persistJournal(confirmed)

        val stable = store.commitConfirmed(confirmed)

        assertEquals(StableIndexState(2, 20, "new-file"), stable)
        assertNull(database.pendingOperationDao().getById("old-op"))
        assertNotNull(database.pendingOperationDao().getById("new-op"))
        assertEquals(IndexSyncStatus.DIRTY, database.indexStateDao().get(IndexStateEntity.SINGLETON_ID)?.syncStatus)
        assertEquals(IndexUpdatePhase.COMMITTED, store.readJournal()?.phase)
        store.clearJournal("journal")
        assertNull(store.readJournal())
    }

    private fun operation(id: String, timestamp: Long) = PendingOperationEntity(
        id = id,
        type = PendingOperationType.CREATE_FOLDER,
        targetId = id,
        payloadJson = "{}",
        remainingMessageIdsJson = null,
        baseRevision = 1,
        candidateRevision = null,
        indexConfirmedAtEpochMillis = null,
        status = PendingOperationStatus.PENDING,
        attempt = 0,
        nextRetryAtEpochMillis = null,
        errorCode = null,
        createdAtEpochMillis = timestamp,
        updatedAtEpochMillis = timestamp,
    )
}
