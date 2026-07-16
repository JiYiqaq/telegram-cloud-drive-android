package com.teledrive.lite.deletion

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeDeletionCoordinatorTest {
    @Test
    fun recordsEveryRemoteResultAndPublishesPartialRecoveryState() = runBlocking {
        val store = FakeStore(listOf(chunk("a", 11), chunk("b", 12), chunk("c", 13)))
        val remote = FakeRemote(
            mapOf(
                11L to true,
                12L to IOException("offline"),
                13L to false,
            ),
        )
        val publisher = FakePublisher()

        val outcome = SafeDeletionCoordinator(store, remote, publisher) { "NETWORK" }
            .execute(FILE_ID)

        assertEquals(DeletionOutcome.PartiallyDeleted(2), outcome)
        assertEquals(
            listOf(
                Recorded("a", true, null),
                Recorded("b", false, "NETWORK"),
                Recorded("c", false, "REMOTE_REJECTED"),
            ),
            store.recorded,
        )
        assertTrue(publisher.partialPublished)
        assertFalse(publisher.removalPublished)
    }

    @Test
    fun publishesRemovalOnlyAfterEveryChunkWasConfirmedDeleted() = runBlocking {
        val store = FakeStore(listOf(chunk("a", 11), chunk("b", 12)))
        val publisher = FakePublisher()

        val outcome = SafeDeletionCoordinator(
            store,
            FakeRemote(mapOf(11L to true, 12L to true)),
            publisher,
        ).execute(FILE_ID)

        assertEquals(DeletionOutcome.Completed, outcome)
        assertTrue(publisher.removalPublished)
        assertFalse(publisher.partialPublished)
    }

    @Test
    fun retryAfterChunkCleanupResumesAtIndexPublication() = runBlocking {
        val publisher = FakePublisher()

        SafeDeletionCoordinator(FakeStore(emptyList()), FakeRemote(emptyMap()), publisher)
            .execute(FILE_ID)

        assertTrue(publisher.removalPublished)
    }

    private fun chunk(id: String, messageId: Long) = PendingChunkDeletion(id, messageId)

    private class FakeStore(
        private val chunks: List<PendingChunkDeletion>,
    ) : SafeDeletionStore {
        val recorded = mutableListOf<Recorded>()

        override suspend fun pendingChunks(fileId: String) = chunks

        override suspend fun recordResult(
            fileId: String,
            chunkId: String,
            deleted: Boolean,
            errorCode: String?,
        ) {
            recorded += Recorded(chunkId, deleted, errorCode)
        }
    }

    private class FakeRemote(
        private val results: Map<Long, Any>,
    ) : DeletionRemote {
        override suspend fun deleteMessage(messageId: Long): Boolean = when (
            val result = results.getValue(messageId)
        ) {
            is Boolean -> result
            is Throwable -> throw result
            else -> error("invalid fixture")
        }
    }

    private class FakePublisher : DeletionIndexPublisher {
        var partialPublished = false
        var removalPublished = false

        override suspend fun publishPartial(fileId: String) {
            partialPublished = true
        }

        override suspend fun publishRemoval(fileId: String) {
            removalPublished = true
        }
    }

    private data class Recorded(
        val chunkId: String,
        val deleted: Boolean,
        val errorCode: String?,
    )

    private companion object {
        const val FILE_ID = "00000000-0000-0000-0000-000000000001"
    }
}
