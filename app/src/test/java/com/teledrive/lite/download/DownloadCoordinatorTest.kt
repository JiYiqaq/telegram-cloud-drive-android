package com.teledrive.lite.download

import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.crypto.KeyDerivationParameters
import com.teledrive.lite.crypto.KeyWrapping
import com.teledrive.lite.settings.SetupCryptoContext
import com.teledrive.lite.upload.FileChunkAssociatedData
import com.teledrive.lite.util.Sha256
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCoordinatorTest {
    @Test
    fun downloadsAuthenticatesAndWritesChunksInPartOrderBeforeFinalizing() = runBlocking {
        val fixture = fixture("abcdefghij".encodeToByteArray(), chunkSize = 4)
        val store = FakeDownloadStore(fixture.file)
        val remote = FakeDownloadRemote(fixture.encryptedByRemoteId)
        val destination = FakeDestination()

        val outcome = coordinator(store, remote, destination).execute("task")

        assertEquals(DownloadOutcome.Completed(FILE_ID, 10), outcome)
        assertEquals(listOf("remote-0", "remote-1", "remote-2"), remote.requests)
        assertArrayEquals("abcdefghij".encodeToByteArray(), destination.committedBytes)
        assertTrue(store.finalized)
        assertFalse(destination.aborted)
    }

    @Test
    fun authenticationFailureDeletesIncompleteDestinationAndNeverFinalizes() = runBlocking {
        val fixture = fixture("abcdefgh".encodeToByteArray(), chunkSize = 4)
        val corrupted = fixture.encryptedByRemoteId.toMutableMap().also { values ->
            values["remote-1"] = values.getValue("remote-1").copyOf().also { it[it.lastIndex] = 1 }
        }
        val store = FakeDownloadStore(fixture.file)
        val destination = FakeDestination()

        val failure = assertThrows(DownloadException::class.java) {
            runBlocking {
                coordinator(store, FakeDownloadRemote(corrupted), destination).execute("task")
            }
        }

        assertEquals(DownloadFailure.AUTHENTICATION_FAILED, failure.failure)
        assertTrue(destination.aborted)
        assertTrue(destination.removed)
        assertFalse(store.finalized)
        assertEquals(0, destination.bytes.size())
    }

    @Test
    fun finalShaMismatchDeletesOutputAndReportsIntegrityFailure() = runBlocking {
        val fixture = fixture("abcdefgh".encodeToByteArray(), chunkSize = 4)
        val wrongHash = fixture.file.copy(sha256 = "00".repeat(32))
        val store = FakeDownloadStore(wrongHash)
        val destination = FakeDestination()

        val failure = assertThrows(DownloadException::class.java) {
            runBlocking {
                coordinator(
                    store,
                    FakeDownloadRemote(fixture.encryptedByRemoteId),
                    destination,
                ).execute("task")
            }
        }

        assertEquals(DownloadFailure.INTEGRITY_FAILED, failure.failure)
        assertTrue(destination.aborted)
        assertTrue(destination.removed)
        assertFalse(store.finalized)
    }

    @Test
    fun remoteEncryptedSizeMismatchIsRejectedBeforeDecryption() = runBlocking {
        val fixture = fixture("abcd".encodeToByteArray(), chunkSize = 4)
        val truncated = fixture.encryptedByRemoteId.toMutableMap().also { values ->
            values["remote-0"] = values.getValue("remote-0").copyOf(3)
        }
        val destination = FakeDestination()

        val failure = assertThrows(DownloadException::class.java) {
            runBlocking {
                coordinator(
                    FakeDownloadStore(fixture.file),
                    FakeDownloadRemote(truncated),
                    destination,
                ).execute("task")
            }
        }

        assertEquals(DownloadFailure.REMOTE_SIZE_MISMATCH, failure.failure)
        assertTrue(destination.aborted)
        assertTrue(destination.removed)
    }

    @Test
    fun networkFailureTruncatesButKeepsDestinationForSafeRestart() = runBlocking {
        val fixture = fixture("abcdefgh".encodeToByteArray(), chunkSize = 4)
        val destination = FakeDestination()
        val remote = object : DownloadRemote {
            override suspend fun downloadChunk(
                telegramFileId: String,
                expectedEncryptedSizeBytes: Long,
            ): ByteArray = throw IOException("offline")
        }

        assertThrows(IOException::class.java) {
            runBlocking {
                coordinator(FakeDownloadStore(fixture.file), remote, destination).execute("task")
            }
        }

        assertTrue(destination.aborted)
        assertFalse(destination.removed)
    }

    private fun coordinator(
        store: DownloadStore,
        remote: DownloadRemote,
        destination: DownloadDestination,
    ) = DownloadCoordinator(
        store = store,
        remote = remote,
        destination = destination,
        cryptoEngine = CryptoEngine(),
        keyWrapping = KeyWrapping(CryptoEngine()),
        cryptoContextProvider = {
            SetupCryptoContext(
                MASTER_KEY,
                KeyDerivationParameters.pbkdf2(ByteArray(16), 1),
            )
        },
        clock = { 1_000 },
    )

    private fun fixture(plaintext: ByteArray, chunkSize: Int): Fixture {
        val engine = CryptoEngine()
        val chunks = plaintext.toList().chunked(chunkSize).ifEmpty { listOf(emptyList()) }
        val encryptedByRemoteId = linkedMapOf<String, ByteArray>()
        val snapshots = chunks.mapIndexed { index, values ->
            val encrypted = engine.encryptChunk(
                DATA_KEY,
                values.toByteArray(),
                FileChunkAssociatedData.create(FILE_ID, index),
            )
            val remoteId = "remote-$index"
            encryptedByRemoteId[remoteId] = encrypted
            DownloadChunkSnapshot(
                partIndex = index,
                telegramFileId = remoteId,
                nonce = engine.extractNonce(encrypted),
                encryptedSizeBytes = encrypted.size.toLong(),
            )
        }
        val wrapped = KeyWrapping(engine).wrap(FILE_ID, MASTER_KEY, DATA_KEY)
        return Fixture(
            file = DownloadFileSnapshot(
                taskId = "task",
                fileId = FILE_ID,
                fileName = "fixture.bin",
                destinationUri = "content://destination/file",
                sizeBytes = plaintext.size.toLong(),
                chunkSizeBytes = chunkSize,
                chunkCount = snapshots.size,
                sha256 = Sha256.digest(plaintext),
                wrappedDataKey = wrapped,
                chunks = snapshots.reversed(),
            ),
            encryptedByRemoteId = encryptedByRemoteId,
        )
    }

    private class FakeDownloadStore(private val file: DownloadFileSnapshot) : DownloadStore {
        var finalized = false
        val progress = mutableListOf<DownloadProgress>()

        override suspend fun load(taskId: String): DownloadFileSnapshot = file

        override suspend fun updateProgress(taskId: String, progress: DownloadProgress) {
            this.progress += progress
        }

        override suspend fun finalizeAfterDestination(taskId: String) {
            finalized = true
        }
    }

    private class FakeDownloadRemote(
        private val encryptedByRemoteId: Map<String, ByteArray>,
    ) : DownloadRemote {
        val requests = mutableListOf<String>()

        override suspend fun downloadChunk(
            telegramFileId: String,
            expectedEncryptedSizeBytes: Long,
        ): ByteArray {
            requests += telegramFileId
            return encryptedByRemoteId.getValue(telegramFileId).copyOf()
        }
    }

    private class FakeDestination : DownloadDestination {
        val bytes = ByteArrayOutputStream()
        var aborted = false
        var removed = false
        var committedBytes: ByteArray? = null

        override fun open(destinationUri: String): DownloadOutput = object : DownloadOutput {
            override fun write(plaintext: ByteArray) {
                bytes.write(plaintext)
            }

            override fun commit() {
                committedBytes = bytes.toByteArray()
            }

            override fun abort(removeDestination: Boolean) {
                aborted = true
                removed = removeDestination
                bytes.reset()
            }
        }
    }

    private data class Fixture(
        val file: DownloadFileSnapshot,
        val encryptedByRemoteId: Map<String, ByteArray>,
    )

    private companion object {
        const val FILE_ID = "00000000-0000-0000-0000-000000000001"
        val MASTER_KEY = ByteArray(32) { it.toByte() }
        val DATA_KEY = ByteArray(32) { (it + 9).toByte() }
    }
}
