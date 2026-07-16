package com.teledrive.lite.sync

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class IndexAtomicUpdaterRecoveryTest {
    @Test
    fun resumesAfterFinalEditWithoutSendingAnotherMessage() = runBlocking {
        val previous = StableIndexState(4, 40, "old-file")
        val final = RemoteIndexDocument(51, "new-file", IndexAtomicUpdater.INDEX_FILE_NAME, 3)
        val local = RecordingLocalStore(previous).apply {
            journal = IndexUpdateJournal(
                operationId = "op",
                baseStableState = previous,
                candidateRevision = 5,
                phase = IndexUpdatePhase.FINAL_EDITED,
                provisionalMessageId = 51,
                finalDocument = final,
            )
        }
        val remote = RecordingRemote(pinned = previous.toRemoteDocument(), finalDocument = final)

        val result = updater(local, remote).resumeOrStart()

        assertEquals(IndexUpdateOutcome.Completed(StableIndexState(5, 51, "new-file")), result)
        assertEquals(
            listOf("getPinned", "pin:51", "getPinned", "getPinned", "unpin:40", "delete:40"),
            remote.calls,
        )
        assertNull(local.journal)
    }

    @Test
    fun refusesToPinWhenRemoteBaseChangedAndLeavesDurableJournal() = runBlocking {
        val previous = StableIndexState(4, 40, "old-file")
        val final = RemoteIndexDocument(51, "new-file", IndexAtomicUpdater.INDEX_FILE_NAME, 3)
        val local = RecordingLocalStore(previous).apply {
            journal = IndexUpdateJournal(
                operationId = "op",
                baseStableState = previous,
                candidateRevision = 5,
                phase = IndexUpdatePhase.FINAL_EDITED,
                provisionalMessageId = 51,
                finalDocument = final,
            )
        }
        val remote = RecordingRemote(
            pinned = RemoteIndexDocument(99, "other-file", IndexAtomicUpdater.INDEX_FILE_NAME, 9),
            finalDocument = final,
        )

        val failure = assertThrows(IndexUpdateException::class.java) {
            runBlocking { updater(local, remote).resumeOrStart() }
        }

        assertEquals(IndexUpdateFailure.REMOTE_BASE_CHANGED, failure.failure)
        assertEquals(listOf("getPinned"), remote.calls)
        assertNotNull(local.journal)
        assertEquals(IndexUpdatePhase.FINAL_EDITED, local.journal?.phase)
        assertEquals(previous, local.stable)
    }

    @Test
    fun crashAfterPinIsRecognizedAsAlreadyConfirmedInsteadOfAFalseFork() = runBlocking {
        val previous = StableIndexState(4, 40, "old-file")
        val final = RemoteIndexDocument(51, "new-file", IndexAtomicUpdater.INDEX_FILE_NAME, 3)
        val local = RecordingLocalStore(previous).apply {
            journal = IndexUpdateJournal(
                operationId = "op",
                baseStableState = previous,
                candidateRevision = 5,
                phase = IndexUpdatePhase.FINAL_EDITED,
                provisionalMessageId = 51,
                finalDocument = final,
            )
        }
        val remote = RecordingRemote(pinned = final, finalDocument = final)

        val result = updater(local, remote).resumeOrStart()

        assertEquals(IndexUpdateOutcome.Completed(StableIndexState(5, 51, "new-file")), result)
        assertEquals(listOf("getPinned", "getPinned", "unpin:40", "delete:40"), remote.calls)
    }

    @Test
    fun refusesCommitWhenPinnedDocumentDoesNotExactlyMatchFinalEdit() = runBlocking {
        val local = RecordingLocalStore(StableIndexState.empty())
        val remote = RecordingRemote(
            pinned = null,
            finalDocument = RemoteIndexDocument(51, "new-file", IndexAtomicUpdater.INDEX_FILE_NAME, 3),
            pinnedOverride = RemoteIndexDocument(51, "wrong-file", IndexAtomicUpdater.INDEX_FILE_NAME, 3),
        )

        val failure = assertThrows(IndexUpdateException::class.java) {
            runBlocking { updater(local, remote).resumeOrStart() }
        }

        assertEquals(IndexUpdateFailure.PIN_CONFIRMATION_MISMATCH, failure.failure)
        assertEquals(0, local.commitCount)
        assertNotNull(local.journal)
    }

    @Test
    fun cleanupFailureIsNonFatalAndNeverRunsBeforeCommit() = runBlocking {
        val previous = StableIndexState(2, 20, "old-file")
        val local = RecordingLocalStore(previous)
        val remote = RecordingRemote(
            pinned = previous.toRemoteDocument(),
            finalDocument = RemoteIndexDocument(51, "new-file", IndexAtomicUpdater.INDEX_FILE_NAME, 3),
            failDelete = true,
        ).also { candidate ->
            candidate.cleanupObserver = { local.committedBeforeCleanup = local.commitCount > 0 }
        }

        val result = updater(local, remote).resumeOrStart()

        assertEquals(IndexUpdateOutcome.Completed(StableIndexState(3, 51, "new-file")), result)
        assertEquals(1, local.commitCount)
        assertEquals(true, local.committedBeforeCleanup)
        assertNull(local.journal)
    }

    private fun updater(local: RecordingLocalStore, remote: RecordingRemote) = IndexAtomicUpdater(
        remote = remote,
        localStore = local,
        candidateFactory = object : IndexCandidateFactory {
            override suspend fun create(request: IndexCandidateRequest) = IndexCandidate(
                revision = request.revision,
                previousIndexMessageId = request.previousIndexMessageId,
                messageId = request.messageId,
                fileName = IndexAtomicUpdater.INDEX_FILE_NAME,
                content = byteArrayOf(1, 2, 3),
            )
        },
        operationIdFactory = { "op" },
    )

    private class RecordingLocalStore(
        var stable: StableIndexState,
    ) : IndexLocalStore {
        var journal: IndexUpdateJournal? = null
        var commitCount = 0
        var committedBeforeCleanup = false

        override suspend fun readStableState() = stable
        override suspend fun readJournal() = journal
        override suspend fun persistJournal(journal: IndexUpdateJournal) {
            this.journal = journal
        }

        override suspend fun commitConfirmed(journal: IndexUpdateJournal): StableIndexState {
            check(journal.phase == IndexUpdatePhase.CONFIRMED)
            commitCount += 1
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

    private class RecordingRemote(
        pinned: RemoteIndexDocument?,
        private val finalDocument: RemoteIndexDocument,
        private val pinnedOverride: RemoteIndexDocument? = null,
        private val failDelete: Boolean = false,
    ) : IndexRemote {
        val calls = mutableListOf<String>()
        private var pinnedDocument = pinned
        private var didPin = false
        var cleanupObserver: (() -> Unit)? = null

        override suspend fun sendProvisional(operationId: String): ProvisionalIndexMessage {
            calls += "send:$operationId"
            return ProvisionalIndexMessage(finalDocument.messageId)
        }

        override suspend fun editToFinal(messageId: Long, candidate: IndexCandidate): RemoteIndexDocument {
            calls += "edit:$messageId"
            return finalDocument
        }

        override suspend fun pin(messageId: Long) {
            calls += "pin:$messageId"
            didPin = true
            pinnedDocument = pinnedOverride ?: finalDocument
        }

        override suspend fun getPinned(): RemoteIndexDocument? {
            calls += "getPinned"
            return pinnedDocument
        }

        override suspend fun unpin(messageId: Long) {
            cleanupObserver?.invoke()
            calls += "unpin:$messageId"
        }

        override suspend fun delete(messageId: Long) {
            cleanupObserver?.invoke()
            calls += "delete:$messageId"
            if (failDelete) throw IOException("cleanup failed")
        }
    }

    private fun StableIndexState.toRemoteDocument(): RemoteIndexDocument? = messageId?.let {
        RemoteIndexDocument(it, checkNotNull(fileId), IndexAtomicUpdater.INDEX_FILE_NAME, 0)
    }
}
