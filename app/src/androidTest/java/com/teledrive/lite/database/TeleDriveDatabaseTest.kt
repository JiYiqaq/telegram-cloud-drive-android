package com.teledrive.lite.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.PendingOperationStatus
import com.teledrive.lite.model.PendingOperationType
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType
import com.teledrive.lite.model.SortDirection
import com.teledrive.lite.model.SortMode
import com.teledrive.lite.repository.DriveRepositoryException
import com.teledrive.lite.repository.DriveRepositoryFailure
import com.teledrive.lite.repository.CloudCacheSnapshot
import com.teledrive.lite.repository.FileRepository
import com.teledrive.lite.repository.TransferRepository
import com.teledrive.lite.repository.TransferRepositoryException
import com.teledrive.lite.repository.TransferRepositoryFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeleDriveDatabaseTest {
    private lateinit var database: TeleDriveDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TeleDriveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun daoFlowsExposeMultilevelFoldersFilesChunksAndSearch() = runBlocking {
        val now = 1_700_000_000_000L
        database.folderDao().upsert(
            FolderEntity("root", "我的云盘", null, now, now),
        )
        database.folderDao().upsert(
            FolderEntity("docs", "资料", "root", now, now),
        )
        database.folderDao().upsert(
            FolderEntity("school", "课程", "docs", now, now),
        )
        database.fileDao().upsert(
            FileEntity(
                id = "file-1",
                name = "讲义.pdf",
                mimeType = "application/pdf",
                sizeBytes = 1234,
                createdAtEpochMillis = now,
                modifiedAtEpochMillis = now,
                uploadedAtEpochMillis = now,
                parentFolderId = "school",
                sha256 = "abc",
                encryptionFormatVersion = 1,
                chunkSizeBytes = 18 * 1024 * 1024,
                chunkCount = 1,
                wrappedDataKey = byteArrayOf(1, 2, 3),
                status = FileStatus.AVAILABLE,
                isCloudIndexed = true,
            ),
        )
        database.chunkDao().upsert(
            ChunkEntity(
                id = "chunk-1",
                fileId = "file-1",
                partIndex = 0,
                messageId = 51,
                telegramFileId = "remote-id",
                nonce = ByteArray(12),
                encryptedSizeBytes = 1268,
                uploadStatus = ChunkUploadStatus.UPLOADED,
            ),
        )

        assertEquals(listOf("docs"), database.folderDao().observeChildren("root").first().map { it.id })
        assertEquals(listOf("file-1"), database.fileDao().observeInFolder("school").first().map { it.id })
        assertEquals(listOf("讲义.pdf"), database.fileDao().search("讲义").first().map { it.name })
        assertEquals(1, database.chunkDao().getForFile("file-1").size)
    }

    @Test
    fun stagedDeleteRetainsMetadataUntilFinalFileCleanup() = runBlocking {
        val now = 1_700_000_000_000L
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, now, now))
        database.folderDao().upsert(FolderEntity("child", "资料", "root", now, now))
        database.fileDao().upsert(
            FileEntity(
                id = "file-1",
                name = "a.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 1,
                createdAtEpochMillis = now,
                modifiedAtEpochMillis = now,
                uploadedAtEpochMillis = null,
                parentFolderId = "child",
                sha256 = null,
                encryptionFormatVersion = 1,
                chunkSizeBytes = 1024,
                chunkCount = 1,
                wrappedDataKey = null,
                status = FileStatus.PENDING,
            ),
        )
        database.chunkDao().upsert(
            ChunkEntity(
                "chunk-1", "file-1", 0, 51, "remote-id", ByteArray(12), 17,
                ChunkUploadStatus.UPLOADED,
            ),
        )
        database.transferTaskDao().upsert(
            TransferTaskEntity(
                id = "task-1",
                fileId = "file-1",
                fileNameSnapshot = "a.bin",
                type = TransferType.UPLOAD,
                status = TransferStatus.QUEUED,
                completedBytes = 0,
                totalBytes = 1,
                currentChunk = 0,
                totalChunks = 1,
                speedBytesPerSecond = 0,
                attempt = 0,
                nextRetryAtEpochMillis = null,
                errorCode = null,
                workRequestId = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )

        database.fileDao().updateStatus("file-1", FileStatus.DELETING, now + 1)
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { database.folderDao().deleteById("child") }
        }
        assertNotNull(database.fileDao().getById("file-1"))
        assertEquals(51L, database.chunkDao().getById("chunk-1")?.messageId)

        database.fileDao().deleteById("file-1")
        assertNull(database.fileDao().getById("file-1"))
        assertNull(database.chunkDao().getById("chunk-1"))
        assertNull(database.transferTaskDao().getById("task-1")?.fileId)
        assertEquals(emptyList<TransferTaskEntity>(), database.transferTaskDao().observeActive().first())
        database.folderDao().deleteById("child")
        assertNull(database.folderDao().getById("child"))
    }

    @Test
    fun repositoryCreatesMovesSortsSearchesAndPlansConfirmedTreeDeletion() = runBlocking {
        val ids = ArrayDeque(listOf("docs", "nested", "archive"))
        var now = 1_700_000_000_000L
        val repository = FileRepository(
            database = database,
            idGenerator = { ids.removeFirst() },
            clock = { now++ },
        )
        repository.initializeRoot()
        repository.createFolder("root", "资料")
        repository.createFolder("docs", "课程")
        repository.createFolder("root", "归档")

        val moveError = assertThrows(DriveRepositoryException::class.java) {
            runBlocking { repository.moveFolder("docs", "nested") }
        }
        assertEquals(DriveRepositoryFailure.INVALID_FOLDER_MOVE, moveError.failure)
        repository.moveFolder("nested", "archive")

        val rootSnapshot = repository.observeDirectory(
            "root",
            SortMode.NAME,
            SortDirection.ASCENDING,
        ).first()
        assertEquals(listOf("archive", "docs"), rootSnapshot.entries.map { it.id })
        assertEquals(listOf("docs"), repository.search("资料").first().map { it.id })

        val confirmationError = assertThrows(DriveRepositoryException::class.java) {
            runBlocking { repository.planFolderDeletion("archive", confirmed = false) }
        }
        assertEquals(DriveRepositoryFailure.NON_EMPTY_CONFIRMATION_REQUIRED, confirmationError.failure)
        val plan = repository.planFolderDeletion("archive", confirmed = true)
        assertEquals(listOf("nested", "archive"), plan.folderIdsInDeletionOrder)
        assertEquals(4, database.pendingOperationDao().getAll().size)
        assertEquals(
            IndexSyncStatus.DIRTY,
            database.indexStateDao().get(IndexStateEntity.SINGLETON_ID)?.syncStatus,
        )
    }

    @Test
    fun fileRenameAndBatchMutationsAreConflictSafeAndAtomic() = runBlocking {
        val now = 1_700_000_000_000L
        val repository = FileRepository(database = database, clock = { now + 1 })
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, now, now))
        database.folderDao().upsert(FolderEntity("left", "左侧", "root", now, now))
        database.folderDao().upsert(FolderEntity("right", "右侧", "root", now, now))
        database.folderDao().upsert(FolderEntity("target", "目标", "root", now, now))
        database.fileDao().upsert(testFile("left-file", "left", FileStatus.PENDING, now))
        database.fileDao().upsert(
            testFile("right-file", "right", FileStatus.PENDING, now).copy(name = "A.BIN"),
        )

        repository.renameFile("left-file", "A.bin")
        assertEquals("A.bin", database.fileDao().getById("left-file")?.name)
        val moveResults = repository.moveFiles(listOf("left-file", "right-file"), "target")
        assertEquals(true, moveResults.first { it.entryId == "left-file" }.succeeded)
        assertEquals(
            DriveRepositoryFailure.NAME_CONFLICT,
            moveResults.first { it.entryId == "right-file" }.failure,
        )
        assertEquals("target", database.fileDao().getById("left-file")?.parentFolderId)
        assertEquals("right", database.fileDao().getById("right-file")?.parentFolderId)

        database.fileDao().upsert(
            testFile("available", "target", FileStatus.AVAILABLE, now).copy(name = "b.bin"),
        )
        val stateResults = repository.transitionFileStatuses(
            listOf("left-file", "available"),
            FileStatus.ENCRYPTING,
        )
        assertEquals(true, stateResults.first { it.entryId == "left-file" }.succeeded)
        assertEquals(
            DriveRepositoryFailure.INVALID_FILE_STATE,
            stateResults.first { it.entryId == "available" }.failure,
        )
        assertEquals(FileStatus.ENCRYPTING, database.fileDao().getById("left-file")?.status)
        assertEquals(FileStatus.AVAILABLE, database.fileDao().getById("available")?.status)
    }

    @Test
    fun repositoryStartsTwoFileDeletionsFromOneStableIndexTransaction() = runBlocking {
        val now = 1_700_000_000_000L
        val repository = FileRepository(database = database, clock = { now })
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, now, now))
        listOf("file-1", "file-2").forEachIndexed { index, fileId ->
            database.fileDao().upsert(testFile(fileId, "root", FileStatus.DOWNLOADING, now))
            database.chunkDao().upsert(
                ChunkEntity(
                    "chunk-$fileId",
                    fileId,
                    0,
                    51L + index,
                    "remote-$fileId",
                    ByteArray(12),
                    17,
                    ChunkUploadStatus.UPLOADED,
                ),
            )
        }
        database.indexStateDao().upsert(
            IndexStateEntity(
                id = IndexStateEntity.SINGLETON_ID,
                formatVersion = 1,
                revision = 4,
                rootFolderId = "root",
                currentIndexMessageId = 90,
                previousIndexMessageId = 89,
                currentIndexFileId = "index-file",
                lastSyncedAtEpochMillis = now,
                syncStatus = IndexSyncStatus.SYNCED,
            ),
        )

        val starts = repository.beginFileDeletions(listOf("file-1", "file-2"))

        assertEquals(2, starts.size)
        assertEquals(
            setOf("file-1", "file-2"),
            starts.map { it.fileId }.toSet(),
        )
        starts.forEach { start ->
            assertEquals(
                4L,
                database.pendingOperationDao().getById(start.operationId)?.baseRevision,
            )
            assertEquals(FileStatus.DELETING, database.fileDao().getById(start.fileId)?.status)
        }
        assertEquals(
            IndexSyncStatus.DIRTY,
            database.indexStateDao().get(IndexStateEntity.SINGLETON_ID)?.syncStatus,
        )
    }

    @Test
    fun repositoryRejectsUnsafeFinalDeletionAndInvalidCloudSnapshotsAtomically() = runBlocking {
        val now = 1_700_000_000_000L
        val repository = FileRepository(database = database, clock = { now })
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, now, now))
        database.fileDao().upsert(testFile("file-1", "root", FileStatus.DOWNLOADING, now))
        database.chunkDao().upsert(
            ChunkEntity(
                "chunk-1", "file-1", 0, 51, "remote-id", ByteArray(12), 17,
                ChunkUploadStatus.UPLOADED,
            ),
        )
        database.indexStateDao().upsert(
            IndexStateEntity(
                id = IndexStateEntity.SINGLETON_ID,
                formatVersion = 1,
                revision = 4,
                rootFolderId = "root",
                currentIndexMessageId = 90,
                previousIndexMessageId = 89,
                currentIndexFileId = "index-file",
                lastSyncedAtEpochMillis = now,
                syncStatus = IndexSyncStatus.SYNCED,
            ),
        )
        database.transferTaskDao().upsert(
            TransferTaskEntity(
                id = "delete-task",
                fileId = "file-1",
                fileNameSnapshot = "a.bin",
                type = TransferType.DOWNLOAD,
                status = TransferStatus.RUNNING,
                completedBytes = 0,
                totalBytes = 1024,
                currentChunk = 0,
                totalChunks = 1,
                speedBytesPerSecond = 50,
                attempt = 0,
                nextRetryAtEpochMillis = null,
                errorCode = null,
                workRequestId = "delete-work",
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        val deletionStart = repository.beginFileDeletion("file-1")
        assertEquals(listOf("delete-work"), deletionStart.canceledWorkRequestIds)
        assertEquals(TransferStatus.CANCELED, database.transferTaskDao().getById("delete-task")?.status)
        val deleteOperation = checkNotNull(
            database.pendingOperationDao().getById(deletionStart.operationId),
        )
        assertEquals(4L, deleteOperation.baseRevision)
        assertEquals("[51]", deleteOperation.remainingMessageIdsJson)
        assertEquals(
            IndexSyncStatus.DIRTY,
            database.indexStateDao().get(IndexStateEntity.SINGLETON_ID)?.syncStatus,
        )

        assertEquals(
            emptyList<String>(),
            repository.observeDirectory("root", SortMode.NAME, SortDirection.ASCENDING)
                .first()
                .entries
                .map { it.id },
        )
        assertEquals(true, repository.markFileDeletionRecoverable("file-1", "WORK_NOT_ENQUEUED"))
        assertEquals(FileStatus.PARTIALLY_DELETED, database.fileDao().getById("file-1")?.status)
        val recoveredOperation = checkNotNull(
            database.pendingOperationDao().getById(deletionStart.operationId),
        )
        assertEquals(PendingOperationStatus.FAILED, recoveredOperation.status)
        assertEquals(1, recoveredOperation.attempt)
        assertEquals("WORK_NOT_ENQUEUED", recoveredOperation.errorCode)
        assertEquals(
            listOf("file-1"),
            repository.observeDirectory("root", SortMode.NAME, SortDirection.ASCENDING)
                .first()
                .entries
                .map { it.id },
        )

        repository.beginFileDeletion("file-1")
        assertEquals(FileStatus.DELETING, database.fileDao().getById("file-1")?.status)
        val resumedOperation = checkNotNull(
            database.pendingOperationDao().getById(deletionStart.operationId),
        )
        assertEquals(PendingOperationStatus.PENDING, resumedOperation.status)
        assertNull(resumedOperation.errorCode)
        assertEquals(
            emptyList<String>(),
            repository.observeDirectory("root", SortMode.NAME, SortDirection.ASCENDING)
                .first()
                .entries
                .map { it.id },
        )

        val confirmedSnapshot = CloudCacheSnapshot(
            folders = listOf(FolderEntity("root", "我的云盘", null, now, now)),
            files = emptyList(),
            chunks = emptyList(),
            indexState = checkNotNull(database.indexStateDao().get(IndexStateEntity.SINGLETON_ID)).copy(
                revision = 5,
                currentIndexMessageId = 91,
                previousIndexMessageId = 90,
                currentIndexFileId = "index-file-5",
                syncStatus = IndexSyncStatus.SYNCED,
            ),
        )

        val remoteChunkError = assertThrows(DriveRepositoryException::class.java) {
            runBlocking { repository.finalizeFileDeletion("file-1", confirmedSnapshot) }
        }
        assertEquals(DriveRepositoryFailure.REMOTE_DELETE_INCOMPLETE, remoteChunkError.failure)
        assertEquals(51L, database.chunkDao().getById("chunk-1")?.messageId)

        repository.recordChunkDeletionResult(
            fileId = "file-1",
            chunkId = "chunk-1",
            deleted = false,
            errorCode = "HTTP_500",
        )
        assertEquals(FileStatus.PARTIALLY_DELETED, database.fileDao().getById("file-1")?.status)
        assertEquals(
            "[51]",
            database.pendingOperationDao().getById(deletionStart.operationId)?.remainingMessageIdsJson,
        )
        repository.recordChunkDeletionResult("file-1", "chunk-1", deleted = true)
        assertEquals(FileStatus.DELETING, database.fileDao().getById("file-1")?.status)
        assertEquals(
            "[]",
            database.pendingOperationDao().getById(deletionStart.operationId)?.remainingMessageIdsJson,
        )
        val revisionError = assertThrows(DriveRepositoryException::class.java) {
            runBlocking {
                repository.finalizeFileDeletion(
                    "file-1",
                    confirmedSnapshot.copy(indexState = confirmedSnapshot.indexState.copy(revision = 4)),
                )
            }
        }
        assertEquals(DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED, revisionError.failure)
        assertNotNull(database.fileDao().getById("file-1"))

        val deletedChunk = checkNotNull(database.chunkDao().getById("chunk-1"))
        database.chunkDao().deleteForFile("file-1")
        val missingChunkError = assertThrows(DriveRepositoryException::class.java) {
            runBlocking { repository.finalizeFileDeletion("file-1", confirmedSnapshot) }
        }
        assertEquals(DriveRepositoryFailure.REMOTE_DELETE_INCOMPLETE, missingChunkError.failure)
        database.chunkDao().upsert(deletedChunk)

        val oldRoot = checkNotNull(database.folderDao().getById("root"))
        val invalidSnapshotError = assertThrows(DriveRepositoryException::class.java) {
            runBlocking {
                repository.replaceCloudCache(
                    folders = listOf(
                        oldRoot,
                        FolderEntity("second-root", "另一根", null, now, now),
                    ),
                    files = emptyList(),
                    chunks = emptyList(),
                    indexState = checkNotNull(
                        database.indexStateDao().get(IndexStateEntity.SINGLETON_ID),
                    ),
                )
            }
        }
        assertEquals(DriveRepositoryFailure.INVALID_CLOUD_SNAPSHOT, invalidSnapshotError.failure)
        assertNotNull(database.fileDao().getById("file-1"))

        val conflictingNameError = assertThrows(DriveRepositoryException::class.java) {
            runBlocking {
                repository.replaceCloudCache(
                    folders = listOf(
                        oldRoot,
                        FolderEntity("folder", "A.bin", "root", now, now),
                    ),
                    files = listOf(testFile("snapshot-file", "root", FileStatus.AVAILABLE, now)),
                    chunks = emptyList(),
                    indexState = checkNotNull(
                        database.indexStateDao().get(IndexStateEntity.SINGLETON_ID),
                    ),
                )
            }
        }
        assertEquals(DriveRepositoryFailure.INVALID_CLOUD_SNAPSHOT, conflictingNameError.failure)
        assertNotNull(database.fileDao().getById("file-1"))

        repository.finalizeFileDeletion("file-1", confirmedSnapshot)
        assertNull(database.fileDao().getById("file-1"))
        assertNull(database.pendingOperationDao().getById(deletionStart.operationId))
        assertEquals(5L, database.indexStateDao().get(IndexStateEntity.SINGLETON_ID)?.revision)
    }

    @Test
    fun cloudCacheReplacementRestoresRecoverablePartialDeletion() = runBlocking {
        val now = 1_700_000_000_000L
        val file = testFile("file-1", "root", FileStatus.PARTIALLY_DELETED, now).copy(
            uploadedAtEpochMillis = now,
            sha256 = "ab".repeat(32),
            wrappedDataKey = byteArrayOf(1),
            isCloudIndexed = true,
        )
        val chunk = ChunkEntity(
            id = "chunk-1",
            fileId = file.id,
            partIndex = 0,
            messageId = 51,
            telegramFileId = "remote-id",
            nonce = ByteArray(12),
            encryptedSizeBytes = 17,
            uploadStatus = ChunkUploadStatus.FAILED,
        )
        val operation = PendingOperationEntity(
            id = "delete:file-1",
            type = PendingOperationType.DELETE,
            targetId = file.id,
            payloadJson = null,
            remainingMessageIdsJson = "[51]",
            baseRevision = 3,
            candidateRevision = null,
            indexConfirmedAtEpochMillis = null,
            status = PendingOperationStatus.FAILED,
            attempt = 1,
            nextRetryAtEpochMillis = null,
            errorCode = "HTTP_500",
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val snapshot = CloudCacheSnapshot(
            folders = listOf(FolderEntity("root", "我的云盘", null, now, now)),
            files = listOf(file),
            chunks = listOf(chunk),
            indexState = IndexStateEntity(
                id = IndexStateEntity.SINGLETON_ID,
                formatVersion = 1,
                revision = 3,
                rootFolderId = "root",
                currentIndexMessageId = 70,
                previousIndexMessageId = 69,
                currentIndexFileId = "index-3",
                lastSyncedAtEpochMillis = now,
                syncStatus = IndexSyncStatus.SYNCED,
            ),
            pendingOperations = listOf(operation),
        )

        val repository = FileRepository(database)
        repository.replaceCloudCache(snapshot)

        assertEquals(FileStatus.PARTIALLY_DELETED, database.fileDao().getById(file.id)?.status)
        assertEquals("[51]", database.pendingOperationDao().getById(operation.id)?.remainingMessageIdsJson)
        assertEquals(ChunkUploadStatus.FAILED, database.chunkDao().getById(chunk.id)?.uploadStatus)
        repository.replaceCloudCache(snapshot)
        repository.recordChunkDeletionResult(file.id, chunk.id, deleted = true)
        assertEquals(
            IndexSyncStatus.DIRTY,
            database.indexStateDao().get(IndexStateEntity.SINGLETON_ID)?.syncStatus,
        )
        val staleReplacement = assertThrows(DriveRepositoryException::class.java) {
            runBlocking { repository.replaceCloudCache(snapshot) }
        }
        assertEquals(
            DriveRepositoryFailure.LOCAL_RECOVERY_DATA_EXISTS,
            staleReplacement.failure,
        )
        assertEquals(
            ChunkUploadStatus.DELETED,
            database.chunkDao().getById(chunk.id)?.uploadStatus,
        )
        assertEquals(
            "[]",
            database.pendingOperationDao().getById(operation.id)?.remainingMessageIdsJson,
        )
    }

    @Test
    fun cloudCacheReplacementPreservesLocalOnlyOrphanCleanupMetadata() = runBlocking {
        val now = 1_700_000_000_000L
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, now, now))
        val localOnlyFile = testFile("local-upload", "root", FileStatus.FAILED, now).copy(
            isCloudIndexed = false,
        )
        database.fileDao().upsert(localOnlyFile)
        database.chunkDao().upsert(
            ChunkEntity(
                id = "local-chunk",
                fileId = localOnlyFile.id,
                partIndex = 0,
                messageId = 88,
                telegramFileId = "orphan-remote",
                nonce = ByteArray(12),
                encryptedSizeBytes = 17,
                uploadStatus = ChunkUploadStatus.FAILED,
            ),
        )
        val incoming = CloudCacheSnapshot(
            folders = listOf(FolderEntity("root", "我的云盘", null, now, now)),
            files = emptyList(),
            chunks = emptyList(),
            indexState = IndexStateEntity(
                id = IndexStateEntity.SINGLETON_ID,
                formatVersion = 1,
                revision = 2,
                rootFolderId = "root",
                currentIndexMessageId = 20,
                previousIndexMessageId = 19,
                currentIndexFileId = "index-2",
                lastSyncedAtEpochMillis = now,
                syncStatus = IndexSyncStatus.SYNCED,
            ),
        )

        val error = assertThrows(DriveRepositoryException::class.java) {
            runBlocking { FileRepository(database).replaceCloudCache(incoming) }
        }

        assertEquals(DriveRepositoryFailure.LOCAL_RECOVERY_DATA_EXISTS, error.failure)
        assertEquals(88L, database.chunkDao().getById("local-chunk")?.messageId)
        assertNotNull(database.fileDao().getById(localOnlyFile.id))
    }

    @Test
    fun transferRepositoryEnforcesProgressAndExplicitRetry() = runBlocking {
        val now = 1_700_000_000_000L
        var tick = now
        database.folderDao().upsert(FolderEntity("root", "我的云盘", null, now, now))
        database.fileDao().upsert(testFile("file-1", "root", FileStatus.UPLOADING, now))
        val repository = TransferRepository(
            database = database,
            idGenerator = { "task-1" },
            clock = { tick++ },
        )

        val queued = repository.enqueue(
            fileId = "file-1",
            type = TransferType.UPLOAD,
            workRequestId = "work-1",
        )
        assertEquals(TransferStatus.QUEUED, queued.status)
        assertEquals("a.bin", queued.fileNameSnapshot)

        repository.transition("task-1", TransferStatus.RUNNING)
        repository.updateProgress("task-1", completedBytes = 512, currentChunk = 1, speedBytesPerSecond = 2048)
        val running = checkNotNull(database.transferTaskDao().getById("task-1"))
        assertEquals(512L, running.completedBytes)
        assertEquals(2048L, running.speedBytesPerSecond)

        val progressError = assertThrows(TransferRepositoryException::class.java) {
            runBlocking {
                repository.updateProgress(
                    "task-1",
                    completedBytes = 128,
                    currentChunk = 1,
                    speedBytesPerSecond = 1,
                )
            }
        }
        assertEquals(TransferRepositoryFailure.INVALID_PROGRESS, progressError.failure)

        val retryAt = tick + 100
        repository.scheduleRetry("task-1", retryAtEpochMillis = retryAt, errorCode = "HTTP_429")
        val waiting = checkNotNull(database.transferTaskDao().getById("task-1"))
        assertEquals(TransferStatus.WAITING_FOR_RETRY, waiting.status)
        assertEquals(retryAt, waiting.nextRetryAtEpochMillis)
        val earlyRetry = assertThrows(TransferRepositoryException::class.java) {
            runBlocking { repository.retry("task-1") }
        }
        assertEquals(TransferRepositoryFailure.RETRY_NOT_READY, earlyRetry.failure)
        tick = retryAt
        repository.retry("task-1")
        assertEquals(1, database.transferTaskDao().getById("task-1")?.attempt)

        repository.transition("task-1", TransferStatus.RUNNING)
        repository.transition("task-1", TransferStatus.FAILED, errorCode = "HTTP_500")
        repository.retry("task-1")
        val retried = checkNotNull(database.transferTaskDao().getById("task-1"))
        assertEquals(TransferStatus.QUEUED, retried.status)
        assertEquals(2, retried.attempt)
        assertNull(retried.errorCode)

        repository.transition("task-1", TransferStatus.RUNNING)
        repository.transition("task-1", TransferStatus.FAILED, errorCode = "LOCAL")
        database.fileDao().deleteById("file-1")
        val orphanRetry = assertThrows(TransferRepositoryException::class.java) {
            runBlocking { repository.retry("task-1") }
        }
        assertEquals(TransferRepositoryFailure.FILE_NOT_FOUND, orphanRetry.failure)
        assertEquals(TransferStatus.FAILED, database.transferTaskDao().getById("task-1")?.status)
    }

    private fun testFile(
        id: String,
        parentId: String,
        status: FileStatus,
        now: Long,
    ): FileEntity = FileEntity(
        id = id,
        name = "a.bin",
        mimeType = "application/octet-stream",
        sizeBytes = 1024,
        createdAtEpochMillis = now,
        modifiedAtEpochMillis = now,
        uploadedAtEpochMillis = null,
        parentFolderId = parentId,
        sha256 = null,
        encryptionFormatVersion = 1,
        chunkSizeBytes = 1024,
        chunkCount = 1,
        wrappedDataKey = null,
        status = status,
        isCloudIndexed = status == FileStatus.AVAILABLE ||
            status == FileStatus.DOWNLOADING ||
            status == FileStatus.CORRUPTED,
    )
}
