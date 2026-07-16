package com.teledrive.lite.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexAtomicUpdaterTest {
    @Test
    fun firstPublishUsesRevisionOneAndCommitsOnlyAfterExactPinConfirmation() = runBlocking {
        val local = FakeIndexLocalStore(StableIndexState.empty())
        val remote = FakeIndexRemote(provisionalMessageId = 101L, finalFileId = "index-file-1")
        val candidates = FakeIndexCandidateFactory()
        val updater = updater(local, remote, candidates)

        val result = updater.resumeOrStart()

        assertEquals(
            IndexUpdateOutcome.Completed(
                StableIndexState(revision = 1L, messageId = 101L, fileId = "index-file-1"),
            ),
            result,
        )
        assertEquals(listOf(1L), candidates.revisions)
        assertEquals(listOf<Long?>(null), candidates.previousMessageIds)
        assertEquals(
            listOf(
                "send:operation-1",
                "edit:101:teledrive_index_v1.bin:4",
                "getPinned",
                "pin:101",
                "getPinned",
            ),
            remote.calls.take(5),
        )
        assertNull(local.journal)
        assertEquals(1L, local.stable.revision)
        assertEquals(101L, local.stable.messageId)
        assertEquals(listOf(IndexUpdatePhase.CONFIRMED), local.atomicCommitInputs.map { it.phase })
    }

    private fun updater(
        local: FakeIndexLocalStore,
        remote: FakeIndexRemote,
        candidates: FakeIndexCandidateFactory,
    ) = IndexAtomicUpdater(
        remote = remote,
        localStore = local,
        candidateFactory = candidates,
        operationIdFactory = { "operation-1" },
    )

    private class FakeIndexCandidateFactory : IndexCandidateFactory {
        val revisions = mutableListOf<Long>()
        val previousMessageIds = mutableListOf<Long?>()

        override suspend fun create(request: IndexCandidateRequest): IndexCandidate {
            revisions += request.revision
            previousMessageIds += request.previousIndexMessageId
            return IndexCandidate(
                revision = request.revision,
                previousIndexMessageId = request.previousIndexMessageId,
                messageId = request.messageId,
                fileName = IndexAtomicUpdater.INDEX_FILE_NAME,
                content = byteArrayOf(1, 2, 3, 4),
            )
        }
    }

    private class FakeIndexRemote(
        private val provisionalMessageId: Long,
        private val finalFileId: String,
    ) : IndexRemote {
        val calls = mutableListOf<String>()
        private var finalDocument: RemoteIndexDocument? = null
        private var pinned: RemoteIndexDocument? = null

        override suspend fun sendProvisional(operationId: String): ProvisionalIndexMessage {
            calls += "send:$operationId"
            return ProvisionalIndexMessage(provisionalMessageId)
        }

        override suspend fun editToFinal(
            messageId: Long,
            candidate: IndexCandidate,
        ): RemoteIndexDocument {
            calls += "edit:$messageId:${candidate.fileName}:${candidate.sizeBytes}"
            return RemoteIndexDocument(
                messageId = messageId,
                fileId = finalFileId,
                fileName = candidate.fileName,
                sizeBytes = candidate.sizeBytes,
            ).also { finalDocument = it }
        }

        override suspend fun pin(messageId: Long) {
            calls += "pin:$messageId"
            pinned = checkNotNull(finalDocument)
        }

        override suspend fun getPinned(): RemoteIndexDocument? {
            calls += "getPinned"
            return pinned
        }

        override suspend fun unpin(messageId: Long) {
            calls += "unpin:$messageId"
        }

        override suspend fun delete(messageId: Long) {
            calls += "delete:$messageId"
        }
    }

    private class FakeIndexLocalStore(
        var stable: StableIndexState,
    ) : IndexLocalStore {
        var journal: IndexUpdateJournal? = null
        val atomicCommitInputs = mutableListOf<IndexUpdateJournal>()

        override suspend fun readStableState(): StableIndexState = stable

        override suspend fun readJournal(): IndexUpdateJournal? = journal

        override suspend fun persistJournal(journal: IndexUpdateJournal) {
            this.journal = journal
        }

        override suspend fun commitConfirmed(journal: IndexUpdateJournal): StableIndexState {
            atomicCommitInputs += journal
            val document = checkNotNull(journal.finalDocument)
            stable = StableIndexState(journal.candidateRevision, document.messageId, document.fileId)
            this.journal = journal.copy(phase = IndexUpdatePhase.COMMITTED)
            return stable
        }

        override suspend fun clearJournal(operationId: String) {
            check(journal?.operationId == operationId)
            journal = null
        }
    }
}
