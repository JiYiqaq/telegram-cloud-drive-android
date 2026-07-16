package com.teledrive.lite.sync

import androidx.room.withTransaction
import com.teledrive.lite.database.IndexStateEntity
import com.teledrive.lite.database.PendingOperationEntity
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.PendingOperationStatus
import com.teledrive.lite.model.PendingOperationType
import com.teledrive.lite.repository.FolderTreeValidator

class RoomIndexLocalStore(
    private val database: TeleDriveDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : IndexLocalStore {
    private val indexStateDao = database.indexStateDao()
    private val operationDao = database.pendingOperationDao()

    override suspend fun readStableState(): StableIndexState = database.withTransaction {
        indexStateDao.get(IndexStateEntity.SINGLETON_ID).toStableState()
    }

    override suspend fun readJournal(): IndexUpdateJournal? = database.withTransaction {
        readJournalInTransaction()
    }

    override suspend fun beginJournal(
        operationId: String,
        base: StableIndexState,
    ): IndexUpdateJournal = database.withTransaction {
        require(readJournalInTransaction() == null) { "An index update is already active" }
        val currentIndex = indexStateDao.get(IndexStateEntity.SINGLETON_ID)
        require(currentIndex.toStableState() == base) { "Stable index changed before journal creation" }
        val includedIds = operationDao.getAll()
            .filter { it.type != PendingOperationType.INDEX_UPDATE }
            .map(PendingOperationEntity::id)
            .toSortedSet()
        val journal = IndexUpdateJournal(
            operationId = operationId,
            baseStableState = base,
            candidateRevision = base.revision + 1,
            phase = IndexUpdatePhase.PREPARED,
            includedOperationIds = includedIds,
        )
        persistJournalInTransaction(journal)
        indexStateDao.upsert(
            (currentIndex ?: emptyIndexState()).copy(syncStatus = IndexSyncStatus.SYNCING),
        )
        journal
    }

    override suspend fun persistJournal(journal: IndexUpdateJournal) = database.withTransaction {
        val existing = readJournalInTransaction()
        require(existing == null || existing.operationId == journal.operationId) {
            "A different index-update journal is active"
        }
        persistJournalInTransaction(journal)
    }

    override suspend fun commitConfirmed(journal: IndexUpdateJournal): StableIndexState =
        database.withTransaction {
            require(journal.phase == IndexUpdatePhase.CONFIRMED)
            require(readJournalInTransaction() == journal) { "Confirmed journal was not durable" }
            val currentIndex = indexStateDao.get(IndexStateEntity.SINGLETON_ID)
            require(currentIndex.toStableState() == journal.baseStableState) {
                "Stable index changed before commit"
            }
            val allOperations = operationDao.getAll()
            val nonJournalOperations = allOperations.filter {
                it.type != PendingOperationType.INDEX_UPDATE
            }
            val existingIds = nonJournalOperations.map(PendingOperationEntity::id).toSet()
            require(journal.includedOperationIds.all(existingIds::contains)) {
                "An included pending operation disappeared before commit"
            }

            nonJournalOperations
                .filter {
                    it.id in journal.includedOperationIds &&
                        it.type != PendingOperationType.DELETE
                }
                .forEach { operation ->
                    require(operationDao.deleteById(operation.id) == 1)
                }
            val remaining = operationDao.getAll().any {
                it.type != PendingOperationType.INDEX_UPDATE
            }
            val document = requireNotNull(journal.finalDocument)
            val now = clock()
            indexStateDao.upsert(
                IndexStateEntity(
                    id = IndexStateEntity.SINGLETON_ID,
                    formatVersion = INDEX_FORMAT_VERSION,
                    revision = journal.candidateRevision,
                    rootFolderId = currentIndex?.rootFolderId ?: FolderTreeValidator.ROOT_ID,
                    currentIndexMessageId = document.messageId,
                    previousIndexMessageId = journal.baseStableState.messageId,
                    currentIndexFileId = document.fileId,
                    lastSyncedAtEpochMillis = now,
                    syncStatus = if (remaining) IndexSyncStatus.DIRTY else IndexSyncStatus.SYNCED,
                ),
            )
            persistJournalInTransaction(journal.copy(phase = IndexUpdatePhase.COMMITTED))
            StableIndexState(journal.candidateRevision, document.messageId, document.fileId)
        }

    override suspend fun clearJournal(operationId: String) = database.withTransaction {
        val journal = readJournalInTransaction()
        require(journal?.operationId == operationId) { "Index-update journal changed" }
        require(journal.phase == IndexUpdatePhase.COMMITTED) { "Only committed journals can be cleared" }
        require(operationDao.deleteById(operationId) == 1)
    }

    private suspend fun readJournalInTransaction(): IndexUpdateJournal? {
        val entities = operationDao.getAll().filter { it.type == PendingOperationType.INDEX_UPDATE }
        require(entities.size <= 1) { "Multiple index-update journals exist" }
        val entity = entities.singleOrNull() ?: return null
        val encoded = entity.payloadJson ?: error("Index-update journal payload is missing")
        val journal = IndexUpdateJournalCodec.decode(encoded)
        require(
            journal.operationId == entity.id &&
                entity.targetId == IndexStateEntity.SINGLETON_ID &&
                journal.baseStableState.revision == entity.baseRevision &&
                journal.candidateRevision == entity.candidateRevision
        ) { "Index-update journal columns do not match payload" }
        return journal
    }

    private suspend fun persistJournalInTransaction(journal: IndexUpdateJournal) {
        val now = clock()
        val existing = operationDao.getById(journal.operationId)
        operationDao.upsert(
            PendingOperationEntity(
                id = journal.operationId,
                type = PendingOperationType.INDEX_UPDATE,
                targetId = IndexStateEntity.SINGLETON_ID,
                payloadJson = IndexUpdateJournalCodec.encode(journal),
                remainingMessageIdsJson = null,
                baseRevision = journal.baseStableState.revision,
                candidateRevision = journal.candidateRevision,
                indexConfirmedAtEpochMillis = if (journal.phase >= IndexUpdatePhase.CONFIRMED) now else null,
                status = PendingOperationStatus.RUNNING,
                attempt = existing?.attempt ?: 0,
                nextRetryAtEpochMillis = null,
                errorCode = null,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            ),
        )
    }

    private fun IndexStateEntity?.toStableState(): StableIndexState = when {
        this == null || revision == 0L -> StableIndexState.empty()
        else -> StableIndexState(
            revision = revision,
            messageId = requireNotNull(currentIndexMessageId),
            fileId = requireNotNull(currentIndexFileId),
        )
    }

    private fun emptyIndexState() = IndexStateEntity(
        id = IndexStateEntity.SINGLETON_ID,
        formatVersion = INDEX_FORMAT_VERSION,
        revision = 0,
        rootFolderId = FolderTreeValidator.ROOT_ID,
        currentIndexMessageId = null,
        previousIndexMessageId = null,
        currentIndexFileId = null,
        lastSyncedAtEpochMillis = null,
        syncStatus = IndexSyncStatus.NOT_INITIALIZED,
    )

    private companion object {
        const val INDEX_FORMAT_VERSION = 1
    }
}
