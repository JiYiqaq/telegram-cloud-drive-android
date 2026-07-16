package com.teledrive.lite.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** The locally committed cloud-index pointer. All three fields change atomically. */
@Serializable
data class StableIndexState(
    val revision: Long,
    val messageId: Long?,
    val fileId: String?,
) {
    init {
        require(revision >= 0)
        require((revision == 0L) == (messageId == null && fileId == null))
        require(messageId == null || messageId > 0)
        require(fileId == null || fileId.isNotBlank())
    }

    companion object {
        fun empty(): StableIndexState = StableIndexState(0, null, null)
    }
}

@Serializable
enum class IndexUpdatePhase {
    PREPARED,
    PROVISIONAL_SENT,
    FINAL_EDITED,
    CONFIRMED,
    COMMITTED,
}

/**
 * Durable state for one publication attempt. Implementations must store this outside the
 * candidate index itself so an INDEX_UPDATE operation can never recursively serialize itself.
 */
@Serializable
data class IndexUpdateJournal(
    val operationId: String,
    val baseStableState: StableIndexState,
    val candidateRevision: Long,
    val phase: IndexUpdatePhase,
    val provisionalMessageId: Long? = null,
    val finalDocument: RemoteIndexDocument? = null,
    val includedOperationIds: Set<String> = emptySet(),
) {
    init {
        require(operationId.isNotBlank())
        require(candidateRevision == baseStableState.revision + 1)
        require(provisionalMessageId == null || provisionalMessageId > 0)
        if (phase >= IndexUpdatePhase.PROVISIONAL_SENT) requireNotNull(provisionalMessageId)
        if (phase >= IndexUpdatePhase.FINAL_EDITED) requireNotNull(finalDocument)
        require(finalDocument == null || finalDocument.messageId == provisionalMessageId)
        require(includedOperationIds.none(String::isBlank))
    }
}

data class IndexCandidateRequest(
    val operationId: String,
    val revision: Long,
    val previousIndexMessageId: Long?,
    val previousIndexFileId: String?,
    val messageId: Long,
    val includedOperationIds: Set<String>,
)

data class IndexCandidatePreparationRequest(
    val operationId: String,
    val revision: Long,
    val previousIndexMessageId: Long?,
    val previousIndexFileId: String?,
    val includedOperationIds: Set<String>,
)

class IndexCandidate(
    val revision: Long,
    val previousIndexMessageId: Long?,
    val messageId: Long,
    val fileName: String,
    content: ByteArray,
) {
    private val value = content.copyOf()

    val content: ByteArray
        get() = value.copyOf()

    val sizeBytes: Long
        get() = value.size.toLong()
}

data class ProvisionalIndexMessage(val messageId: Long) {
    init {
        require(messageId > 0)
    }
}

@Serializable
data class RemoteIndexDocument(
    val messageId: Long,
    val fileId: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String = INDEX_MIME_TYPE,
) {
    init {
        require(messageId > 0)
        require(fileId.isNotBlank())
        require(fileName.isNotBlank())
        require(sizeBytes >= 0)
        require(mimeType.isNotBlank())
    }

    companion object {
        const val INDEX_MIME_TYPE = "application/octet-stream"
    }
}

interface IndexCandidateFactory {
    /** Freezes the exact local snapshot before the first network suspension. */
    suspend fun prepare(request: IndexCandidatePreparationRequest) = Unit

    suspend fun create(request: IndexCandidateRequest): IndexCandidate

    /** Removes an encrypted cached candidate after the publication journal is cleared. */
    suspend fun clear(operationId: String) = Unit
}

interface IndexRemote {
    suspend fun sendProvisional(operationId: String): ProvisionalIndexMessage
    suspend fun editToFinal(messageId: Long, candidate: IndexCandidate): RemoteIndexDocument
    suspend fun pin(messageId: Long)
    suspend fun getPinned(): RemoteIndexDocument?
    suspend fun unpin(messageId: Long)
    suspend fun delete(messageId: Long)
}

interface IndexLocalStore {
    suspend fun readStableState(): StableIndexState
    suspend fun readJournal(): IndexUpdateJournal?

    /** Override to atomically freeze the pending-operation IDs included in the candidate. */
    suspend fun beginJournal(operationId: String, base: StableIndexState): IndexUpdateJournal =
        IndexUpdateJournal(
            operationId = operationId,
            baseStableState = base,
            candidateRevision = base.revision + 1,
            phase = IndexUpdatePhase.PREPARED,
        )

    suspend fun persistJournal(journal: IndexUpdateJournal)

    /**
     * Atomically commits the new stable pointer, consumes only includedOperationIds, and marks
     * the journal COMMITTED. Operations created after beginJournal must remain pending.
     */
    suspend fun commitConfirmed(journal: IndexUpdateJournal): StableIndexState
    suspend fun clearJournal(operationId: String)
}

sealed interface IndexUpdateOutcome {
    data class Completed(val stableState: StableIndexState) : IndexUpdateOutcome
}

enum class IndexUpdateFailure {
    INVALID_LOCAL_JOURNAL,
    INVALID_CANDIDATE,
    INVALID_REMOTE_DOCUMENT,
    REMOTE_BASE_CHANGED,
    PIN_CONFIRMATION_MISMATCH,
}

class IndexUpdateException(
    val failure: IndexUpdateFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.name, cause)

/**
 * Crash-resumable publication protocol:
 * send provisional -> edit the same message -> verify base -> pin -> confirm exact pin -> commit.
 */
class IndexAtomicUpdater(
    private val remote: IndexRemote,
    private val localStore: IndexLocalStore,
    private val candidateFactory: IndexCandidateFactory,
    private val operationIdFactory: () -> String,
) {
    private val mutex = Mutex()

    suspend fun resumeOrStart(): IndexUpdateOutcome = mutex.withLock {
        var journal = localStore.readJournal() ?: createJournal()

        while (journal.phase != IndexUpdatePhase.COMMITTED) {
            journal = when (journal.phase) {
                IndexUpdatePhase.PREPARED -> sendProvisional(journal)
                IndexUpdatePhase.PROVISIONAL_SENT -> editFinalDocument(journal)
                IndexUpdatePhase.FINAL_EDITED -> pinAndConfirm(journal)
                IndexUpdatePhase.CONFIRMED -> commitIfStillPinned(journal)
                IndexUpdatePhase.COMMITTED -> journal
            }
        }
        finishCommitted(journal)
    }

    private suspend fun createJournal(): IndexUpdateJournal {
        val operationId = operationIdFactory()
        if (operationId.isBlank()) fail(IndexUpdateFailure.INVALID_LOCAL_JOURNAL)
        val base = localStore.readStableState()
        val journal = try {
            localStore.beginJournal(operationId, base)
        } catch (error: IllegalArgumentException) {
            throw IndexUpdateException(IndexUpdateFailure.INVALID_LOCAL_JOURNAL, error)
        }
        if (
            journal.operationId != operationId ||
            journal.baseStableState != base ||
            journal.candidateRevision != base.revision + 1 ||
            journal.phase != IndexUpdatePhase.PREPARED
        ) {
            fail(IndexUpdateFailure.INVALID_LOCAL_JOURNAL)
        }
        localStore.persistJournal(journal)
        return journal
    }

    private suspend fun sendProvisional(journal: IndexUpdateJournal): IndexUpdateJournal {
        candidateFactory.prepare(
            IndexCandidatePreparationRequest(
                operationId = journal.operationId,
                revision = journal.candidateRevision,
                previousIndexMessageId = journal.baseStableState.messageId,
                previousIndexFileId = journal.baseStableState.fileId,
                includedOperationIds = journal.includedOperationIds,
            ),
        )
        val sent = remote.sendProvisional(journal.operationId)
        return journal.copy(
            phase = IndexUpdatePhase.PROVISIONAL_SENT,
            provisionalMessageId = sent.messageId,
        ).also { localStore.persistJournal(it) }
    }

    private suspend fun editFinalDocument(journal: IndexUpdateJournal): IndexUpdateJournal {
        val messageId = journal.provisionalMessageId
            ?: fail(IndexUpdateFailure.INVALID_LOCAL_JOURNAL)
        val candidate = candidateFactory.create(
            IndexCandidateRequest(
                operationId = journal.operationId,
                revision = journal.candidateRevision,
                previousIndexMessageId = journal.baseStableState.messageId,
                previousIndexFileId = journal.baseStableState.fileId,
                messageId = messageId,
                includedOperationIds = journal.includedOperationIds,
            ),
        )
        if (
            candidate.revision != journal.candidateRevision ||
            candidate.previousIndexMessageId != journal.baseStableState.messageId ||
            candidate.messageId != messageId ||
            candidate.fileName != INDEX_FILE_NAME ||
            candidate.sizeBytes <= 0
        ) {
            fail(IndexUpdateFailure.INVALID_CANDIDATE)
        }

        val document = remote.editToFinal(messageId, candidate)
        if (
            document.messageId != messageId ||
            document.fileName != candidate.fileName ||
            document.sizeBytes != candidate.sizeBytes ||
            document.mimeType != RemoteIndexDocument.INDEX_MIME_TYPE
        ) {
            fail(IndexUpdateFailure.INVALID_REMOTE_DOCUMENT)
        }
        return journal.copy(
            phase = IndexUpdatePhase.FINAL_EDITED,
            finalDocument = document,
        ).also { localStore.persistJournal(it) }
    }

    private suspend fun pinAndConfirm(journal: IndexUpdateJournal): IndexUpdateJournal {
        val finalDocument = journal.finalDocument
            ?: fail(IndexUpdateFailure.INVALID_LOCAL_JOURNAL)
        val currentPinned = remote.getPinned()
        if (currentPinned == finalDocument) {
            return journal.copy(phase = IndexUpdatePhase.CONFIRMED)
                .also { localStore.persistJournal(it) }
        }
        if (!matchesBase(currentPinned, journal.baseStableState)) {
            fail(IndexUpdateFailure.REMOTE_BASE_CHANGED)
        }

        remote.pin(finalDocument.messageId)
        if (remote.getPinned() != finalDocument) {
            fail(IndexUpdateFailure.PIN_CONFIRMATION_MISMATCH)
        }
        return journal.copy(phase = IndexUpdatePhase.CONFIRMED)
            .also { localStore.persistJournal(it) }
    }

    private suspend fun commitIfStillPinned(journal: IndexUpdateJournal): IndexUpdateJournal {
        val finalDocument = journal.finalDocument
            ?: fail(IndexUpdateFailure.INVALID_LOCAL_JOURNAL)
        if (remote.getPinned() != finalDocument) {
            fail(IndexUpdateFailure.PIN_CONFIRMATION_MISMATCH)
        }
        localStore.commitConfirmed(journal)
        return journal.copy(phase = IndexUpdatePhase.COMMITTED)
    }

    private suspend fun finishCommitted(journal: IndexUpdateJournal): IndexUpdateOutcome.Completed {
        val stable = localStore.readStableState()
        val finalDocument = journal.finalDocument
            ?: fail(IndexUpdateFailure.INVALID_LOCAL_JOURNAL)
        if (
            stable.revision != journal.candidateRevision ||
            stable.messageId != finalDocument.messageId ||
            stable.fileId != finalDocument.fileId
        ) {
            fail(IndexUpdateFailure.INVALID_LOCAL_JOURNAL)
        }

        val previousMessageId = journal.baseStableState.messageId
        if (previousMessageId != null && previousMessageId != finalDocument.messageId) {
            runCatching { remote.unpin(previousMessageId) }
            runCatching { remote.delete(previousMessageId) }
        }
        localStore.clearJournal(journal.operationId)
        runCatching { candidateFactory.clear(journal.operationId) }
        return IndexUpdateOutcome.Completed(stable)
    }

    private fun matchesBase(remoteDocument: RemoteIndexDocument?, base: StableIndexState): Boolean =
        if (base.revision == 0L) {
            remoteDocument == null
        } else {
            remoteDocument?.messageId == base.messageId && remoteDocument?.fileId == base.fileId
        }

    private fun fail(failure: IndexUpdateFailure): Nothing = throw IndexUpdateException(failure)

    companion object {
        const val INDEX_FILE_NAME = "teledrive_index_v1.bin"
    }
}
