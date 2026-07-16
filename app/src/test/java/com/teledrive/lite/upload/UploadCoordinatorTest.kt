package com.teledrive.lite.upload

import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.crypto.KeyDerivationParameters
import com.teledrive.lite.crypto.KeyWrapping
import com.teledrive.lite.settings.SetupCryptoContext
import com.teledrive.lite.telegram.TelegramApiException
import com.teledrive.lite.telegram.TelegramFailure
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadCoordinatorTest {
    @Test
    fun failedChunkIsNotIndexedAndResumeSkipsEveryDurablyUploadedChunk() = runBlocking {
        val bytes = "abcdefghij".encodeToByteArray()
        val store = FakeUploadStore(
            UploadFileSnapshot(
                taskId = "task",
                fileId = FILE_ID,
                originalName = "private-name.txt",
                sourceUri = "content://source/file",
                sizeBytes = bytes.size.toLong(),
                chunkSizeBytes = 4,
                chunkCount = 3,
                sha256 = null,
                wrappedDataKey = null,
            ),
        )
        val input = FakeInput(bytes)
        val firstRemote = FakeRemote(
            failOnCall = 2,
            failure = TelegramApiException(
                TelegramFailure.Api(429, "retry later", 1, null, 200),
            ),
        )
        val publisher = FakePublisher()
        val coordinator = coordinator(store, input, firstRemote, publisher)

        assertThrows(TelegramApiException::class.java) {
            runBlocking { coordinator.execute("task") }
        }

        assertEquals(setOf(0), store.uploaded.keys)
        assertEquals(0, publisher.calls)
        assertFalse(store.finalized)

        val resumedRemote = FakeRemote()
        val outcome = coordinator(store, input, resumedRemote, publisher).execute("task")

        assertEquals(UploadOutcome.Completed(FILE_ID, 3, bytes.size.toLong()), outcome)
        assertEquals(listOf(1, 2), resumedRemote.partIndexes)
        assertTrue(resumedRemote.fileNames.all { it.matches(Regex("td_[0-9a-f-]+_[0-9]{6}\\.bin")) })
        assertTrue(resumedRemote.fileNames.none { "private-name" in it })
        assertEquals(1, publisher.calls)
        assertTrue(store.finalized)
        assertEquals(3, store.uploaded.size)
        assertEquals(4, input.opens)
    }

    @Test
    fun uncertainSendLeavesDurableInFlightMarkerAndCannotBeBlindlyResumed() = runBlocking {
        val bytes = "abcdefghij".encodeToByteArray()
        val store = FakeUploadStore(
            UploadFileSnapshot(
                "task",
                FILE_ID,
                "x.bin",
                "content://x",
                bytes.size.toLong(),
                4,
                3,
                null,
                null,
            ),
        )
        val firstRemote = FakeRemote(failOnCall = 2, failure = IOException("result unknown"))

        val firstFailure = assertThrows(UploadException::class.java) {
            runBlocking {
                coordinator(store, FakeInput(bytes), firstRemote, FakePublisher()).execute("task")
            }
        }
        assertEquals(UploadFailure.REMOTE_RESULT_UNKNOWN, firstFailure.failure)
        assertEquals(setOf(1), store.sending.keys)

        val resumedRemote = FakeRemote()
        val resumedFailure = assertThrows(UploadException::class.java) {
            runBlocking {
                coordinator(store, FakeInput(bytes), resumedRemote, FakePublisher()).execute("task")
            }
        }
        assertEquals(UploadFailure.REMOTE_RESULT_UNKNOWN, resumedFailure.failure)
        assertEquals(0, resumedRemote.calls)
    }

    @Test
    fun changedProviderSnapshotIsRejectedBeforeAnyMismatchingChunkIsSent() = runBlocking {
        val expected = "abcdefgh".encodeToByteArray()
        val changed = "xxxxefgh".encodeToByteArray()
        val store = FakeUploadStore(
            UploadFileSnapshot(
                "task",
                FILE_ID,
                "x.bin",
                "content://x",
                expected.size.toLong(),
                4,
                2,
                null,
                null,
            ),
        )
        val remote = FakeRemote()
        val input = SequencedInput(listOf(expected, changed))

        val failure = assertThrows(UploadException::class.java) {
            runBlocking { coordinator(store, input, remote, FakePublisher()).execute("task") }
        }

        assertEquals(UploadFailure.SOURCE_CHANGED, failure.failure)
        assertEquals(0, remote.calls)
        assertTrue(store.uploaded.isEmpty())
    }

    @Test
    fun unreadableSourceIsClassifiedAndNeverContactsTelegram() = runBlocking {
        val store = FakeUploadStore(
            UploadFileSnapshot(
                "task",
                FILE_ID,
                "missing.bin",
                "content://missing",
                1,
                4,
                1,
                null,
                null,
            ),
        )
        val remote = FakeRemote()
        val unavailable = object : UploadInput {
            override fun open(sourceUri: String): InputStream =
                throw IOException("provider disappeared")
        }

        val failure = assertThrows(UploadException::class.java) {
            runBlocking {
                coordinator(store, unavailable, remote, FakePublisher()).execute("task")
            }
        }

        assertEquals(UploadFailure.SOURCE_UNAVAILABLE, failure.failure)
        assertEquals(0, remote.calls)
    }

    @Test
    fun indexFailureKeepsUploadedChunksResumableAndNeverFinalizesFile() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val store = FakeUploadStore(
            UploadFileSnapshot("task", FILE_ID, "x.bin", "content://x", 3, 4, 1, null, null),
        )
        val publisher = FakePublisher(fail = true)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                coordinator(store, FakeInput(bytes), FakeRemote(), publisher).execute("task")
            }
        }

        assertEquals(setOf(0), store.uploaded.keys)
        assertFalse(store.finalized)
    }

    private fun coordinator(
        store: UploadStore,
        input: UploadInput,
        remote: UploadRemote,
        publisher: UploadIndexPublisher,
    ) = UploadCoordinator(
        store = store,
        input = input,
        remote = remote,
        indexPublisher = publisher,
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

    private class FakeInput(private val bytes: ByteArray) : UploadInput {
        var opens = 0
        override fun open(sourceUri: String): InputStream {
            opens += 1
            return ByteArrayInputStream(bytes)
        }
    }

    private class SequencedInput(values: List<ByteArray>) : UploadInput {
        private val remaining = ArrayDeque(values)
        override fun open(sourceUri: String): InputStream =
            ByteArrayInputStream(remaining.removeFirst())
    }

    private class FakeUploadStore(
        private var file: UploadFileSnapshot,
    ) : UploadStore {
        val uploaded = mutableMapOf<Int, UploadedChunk>()
        val sending = mutableMapOf<Int, ExpectedUploadChunk>()
        private var expected = emptyList<ExpectedUploadChunk>()
        var finalized = false

        override suspend fun load(taskId: String): UploadResumeState {
            if (sending.isNotEmpty()) throw UploadException(UploadFailure.REMOTE_RESULT_UNKNOWN)
            return UploadResumeState(file, expected, uploaded.values.sortedBy { it.partIndex })
        }

        override suspend fun persistSecurityMetadata(
            taskId: String,
            sha256: String,
            wrappedDataKey: ByteArray,
            expectedChunks: List<ExpectedUploadChunk>,
        ) {
            file = file.copy(sha256 = sha256, wrappedDataKey = wrappedDataKey.copyOf())
            expected = expectedChunks
        }

        override suspend fun markChunkSending(
            taskId: String,
            chunk: SendingUploadChunk,
        ) {
            check(chunk.partIndex !in sending)
            sending[chunk.partIndex] = expected.single { it.partIndex == chunk.partIndex }
        }

        override suspend fun discardSendingChunk(taskId: String, partIndex: Int) {
            sending.remove(partIndex)
        }

        override suspend fun recordUploadedChunk(taskId: String, chunk: UploadedChunk) {
            check(sending.remove(chunk.partIndex) != null)
            uploaded[chunk.partIndex] = chunk
        }

        override suspend fun updateProgress(taskId: String, progress: UploadProgress) = Unit

        override suspend fun publishAndFinalize(taskId: String, publish: suspend () -> Unit) {
            publish()
            finalized = true
        }
    }

    private class FakeRemote(
        private val failOnCall: Int? = null,
        private val failure: Exception = IOException("network result unknown"),
    ) : UploadRemote {
        var calls = 0
        val partIndexes = mutableListOf<Int>()
        val fileNames = mutableListOf<String>()

        override suspend fun sendChunk(
            fileName: String,
            encryptedBytes: ByteArray,
            partIndex: Int,
        ): UploadedRemoteChunk {
            calls += 1
            if (calls == failOnCall) throw failure
            partIndexes += partIndex
            fileNames += fileName
            return UploadedRemoteChunk(
                messageId = 100L + partIndex,
                telegramFileId = "remote-$partIndex",
                encryptedSizeBytes = encryptedBytes.size.toLong(),
            )
        }
    }

    private class FakePublisher(private val fail: Boolean = false) : UploadIndexPublisher {
        var calls = 0
        override suspend fun publish(requiredFileId: String) {
            calls += 1
            check(requiredFileId == FILE_ID)
            if (fail) error("index failed")
        }
    }

    private companion object {
        const val FILE_ID = "00000000-0000-0000-0000-000000000001"
        val MASTER_KEY = ByteArray(32) { it.toByte() }
    }
}
