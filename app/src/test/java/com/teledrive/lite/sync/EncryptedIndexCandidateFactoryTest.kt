package com.teledrive.lite.sync

import com.teledrive.lite.crypto.CloudIndexEnvelopeCryptor
import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.crypto.KeyDerivationParameters
import com.teledrive.lite.database.FolderEntity
import com.teledrive.lite.database.IndexStateEntity
import com.teledrive.lite.index.EncryptedIndexCodec
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.repository.CloudCacheSnapshot
import com.teledrive.lite.settings.SetupCryptoContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedIndexCandidateFactoryTest {
    @Test
    fun persistsEncryptedCandidateAndReusesItAfterRestartWithoutReadingChangedRoomState() = runBlocking {
        val source = RecordingSnapshotSource(snapshot())
        val artifacts = MemoryArtifacts()
        val factory = factory(source, artifacts)
        val request = IndexCandidateRequest("op", 1, null, null, 71, emptySet())

        factory.prepare(IndexCandidatePreparationRequest("op", 1, null, null, emptySet()))
        assertEquals(1, source.reads)
        source.failReads = true
        val first = factory.create(request)
        val resumed = factory(source, artifacts).create(request)

        assertEquals(1, source.reads)
        assertTrue(first.content.contentEquals(resumed.content))
        assertFalse(first.content.decodeToString().contains("TeleDrive"))
        factory.clear("op")
        assertEquals(null, artifacts.load("op"))
    }

    @Test
    fun cachedArtifactForDifferentSelfMessageIsRejected() = runBlocking {
        val source = RecordingSnapshotSource(snapshot())
        val artifacts = MemoryArtifacts()
        val factory = factory(source, artifacts)
        factory.create(IndexCandidateRequest("op", 1, null, null, 71, emptySet()))

        val failure = assertThrows(IndexCandidateException::class.java) {
            runBlocking {
                factory.create(IndexCandidateRequest("op", 1, null, null, 72, emptySet()))
            }
        }
        assertEquals(IndexCandidateFailure.ARTIFACT_MISMATCH, failure.failure)
    }

    private fun factory(source: IndexSnapshotSource, artifacts: IndexCandidateArtifactStore) =
        EncryptedIndexCandidateFactory(
            snapshotSource = source,
            cryptoContextProvider = {
                SetupCryptoContext(
                    MASTER_KEY,
                    KeyDerivationParameters.pbkdf2(ByteArray(16) { it.toByte() }, 1),
                )
            },
            encryptedIndexCodec = EncryptedIndexCodec(CloudIndexEnvelopeCryptor(CryptoEngine())),
            artifactStore = artifacts,
            appVersion = "0.1.0",
            clock = { 2_000 },
        )

    private class RecordingSnapshotSource(
        private val snapshot: CloudCacheSnapshot,
    ) : IndexSnapshotSource {
        var reads = 0
        var failReads = false
        override suspend fun read(includedOperationIds: Set<String>): CloudCacheSnapshot {
            if (failReads) error("Room state changed")
            reads += 1
            return snapshot
        }
    }

    private class MemoryArtifacts : IndexCandidateArtifactStore {
        private val values = mutableMapOf<String, ByteArray>()
        override fun load(operationId: String): ByteArray? = values[operationId]?.copyOf()
        override fun save(operationId: String, bytes: ByteArray) {
            check(operationId !in values)
            values[operationId] = bytes.copyOf()
        }
        override fun delete(operationId: String) {
            values.remove(operationId)?.fill(0)
        }
    }

    private fun snapshot() = CloudCacheSnapshot(
        folders = listOf(FolderEntity("root", "TeleDrive", null, 1_000, 1_000)),
        files = emptyList(),
        chunks = emptyList(),
        indexState = IndexStateEntity(
            id = IndexStateEntity.SINGLETON_ID,
            formatVersion = 1,
            revision = 0,
            rootFolderId = "root",
            currentIndexMessageId = null,
            previousIndexMessageId = null,
            currentIndexFileId = null,
            lastSyncedAtEpochMillis = null,
            syncStatus = IndexSyncStatus.SYNCING,
        ),
    )

    private companion object {
        val MASTER_KEY = ByteArray(32) { (it + 11).toByte() }
    }
}
