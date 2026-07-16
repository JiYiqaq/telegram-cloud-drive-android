package com.teledrive.lite.upload

import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.crypto.KeyWrapping
import com.teledrive.lite.settings.SetupCryptoContext
import com.teledrive.lite.telegram.TelegramApiException
import com.teledrive.lite.telegram.TelegramFailure
import com.teledrive.lite.util.SecureErase
import com.teledrive.lite.util.Sha256
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.min
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class UploadFileSnapshot(
    val taskId: String,
    val fileId: String,
    val originalName: String,
    val sourceUri: String,
    val sizeBytes: Long,
    val chunkSizeBytes: Int,
    val chunkCount: Int,
    val sha256: String?,
    val wrappedDataKey: ByteArray?,
)

data class UploadedChunk(
    val partIndex: Int,
    val messageId: Long,
    val telegramFileId: String,
    val nonce: ByteArray,
    val encryptedSizeBytes: Long,
)

data class ExpectedUploadChunk(
    val partIndex: Int,
    val plaintextSha256: String,
    val plaintextSizeBytes: Long,
)

data class SendingUploadChunk(
    val partIndex: Int,
    val nonce: ByteArray,
    val encryptedSizeBytes: Long,
)

data class UploadResumeState(
    val file: UploadFileSnapshot,
    val expectedChunks: List<ExpectedUploadChunk>,
    val uploadedChunks: List<UploadedChunk>,
)

data class UploadedRemoteChunk(
    val messageId: Long,
    val telegramFileId: String,
    val encryptedSizeBytes: Long,
)

data class UploadProgress(
    val completedBytes: Long,
    val totalBytes: Long,
    val completedChunks: Int,
    val totalChunks: Int,
    val speedBytesPerSecond: Long,
)

sealed interface UploadOutcome {
    data class Completed(
        val fileId: String,
        val chunkCount: Int,
        val sizeBytes: Long,
    ) : UploadOutcome
}

interface UploadInput {
    fun open(sourceUri: String): InputStream
}

interface UploadStore {
    suspend fun load(taskId: String): UploadResumeState
    suspend fun persistSecurityMetadata(
        taskId: String,
        sha256: String,
        wrappedDataKey: ByteArray,
        expectedChunks: List<ExpectedUploadChunk>,
    )
    suspend fun markChunkSending(taskId: String, chunk: SendingUploadChunk)
    suspend fun discardSendingChunk(taskId: String, partIndex: Int)
    suspend fun recordUploadedChunk(taskId: String, chunk: UploadedChunk)
    suspend fun updateProgress(taskId: String, progress: UploadProgress)
    suspend fun publishAndFinalize(taskId: String, publish: suspend () -> Unit)
}

interface UploadRemote {
    suspend fun sendChunk(
        fileName: String,
        encryptedBytes: ByteArray,
        partIndex: Int,
    ): UploadedRemoteChunk
}

fun interface UploadIndexPublisher {
    suspend fun publish(requiredFileId: String)
}

enum class UploadFailure {
    INVALID_STATE,
    SOURCE_CHANGED,
    CRYPTO_CONTEXT_MISSING,
    INVALID_REMOTE_RESPONSE,
    REMOTE_RESULT_UNKNOWN,
    SOURCE_UNAVAILABLE,
}

class UploadException(
    val failure: UploadFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.name, cause)

class UploadCoordinator(
    private val store: UploadStore,
    private val input: UploadInput,
    private val remote: UploadRemote,
    private val indexPublisher: UploadIndexPublisher,
    private val cryptoEngine: CryptoEngine,
    private val keyWrapping: KeyWrapping,
    private val cryptoContextProvider: () -> SetupCryptoContext?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        taskId: String,
        ensureActive: () -> Unit = {},
        onProgress: suspend (UploadProgress) -> Unit = {},
    ): UploadOutcome.Completed {
        val initial = store.load(taskId)
        requireValid(initial)
        val file = initial.file
        val context = cryptoContextProvider()
            ?: fail(UploadFailure.CRYPTO_CONTEXT_MISSING)
        context.use {
            val dataKey = obtainDataKey(initial, context, ensureActive)
            try {
                val resumed = store.load(taskId)
                requireValid(resumed)
                uploadAllChunks(
                    file = resumed.file,
                    expectedChunks = resumed.expectedChunks,
                    initialUploadedChunks = resumed.uploadedChunks,
                    dataKey = dataKey,
                    ensureActive = ensureActive,
                    onProgress = onProgress,
                )
                ensureActive()
                withContext(NonCancellable) {
                    store.publishAndFinalize(taskId) {
                        indexPublisher.publish(file.fileId)
                    }
                }
                return UploadOutcome.Completed(file.fileId, file.chunkCount, file.sizeBytes)
            } finally {
                SecureErase.wipe(dataKey)
            }
        }
    }

    private suspend fun obtainDataKey(
        state: UploadResumeState,
        context: SetupCryptoContext,
        ensureActive: () -> Unit,
    ): ByteArray {
        val file = state.file
        val sha256 = file.sha256
        val wrapped = file.wrappedDataKey
        if ((sha256 == null) != (wrapped == null)) fail(UploadFailure.INVALID_STATE)
        ensureActive()
        val sourceManifest = inspectSource(file, ensureActive)
        if (sha256 != null && wrapped != null) {
            if (
                !SHA_256.matches(sha256) ||
                sourceManifest.sha256 != sha256 ||
                sourceManifest.sizeBytes != file.sizeBytes ||
                sourceManifest.chunks != state.expectedChunks
            ) {
                fail(UploadFailure.SOURCE_CHANGED)
            }
            val wrappedCopy = wrapped.copyOf()
            return try {
                context.withMasterKey { masterKey ->
                    keyWrapping.unwrap(file.fileId, masterKey, wrappedCopy)
                }
            } finally {
                SecureErase.wipe(wrappedCopy)
            }
        }

        if (state.expectedChunks.isNotEmpty()) fail(UploadFailure.INVALID_STATE)
        if (sourceManifest.sizeBytes != file.sizeBytes) fail(UploadFailure.SOURCE_CHANGED)
        val dataKey = cryptoEngine.generateDataKey()
        var wrappedDataKey: ByteArray? = null
        try {
            wrappedDataKey = context.withMasterKey { masterKey ->
                keyWrapping.wrap(file.fileId, masterKey, dataKey)
            }
            store.persistSecurityMetadata(
                file.taskId,
                sourceManifest.sha256,
                wrappedDataKey,
                sourceManifest.chunks,
            )
            return dataKey.copyOf()
        } finally {
            wrappedDataKey?.let(SecureErase::wipe)
            SecureErase.wipe(dataKey)
        }
    }

    private suspend fun uploadAllChunks(
        file: UploadFileSnapshot,
        expectedChunks: List<ExpectedUploadChunk>,
        initialUploadedChunks: List<UploadedChunk>,
        dataKey: ByteArray,
        ensureActive: () -> Unit,
        onProgress: suspend (UploadProgress) -> Unit,
    ) {
        val uploaded = initialUploadedChunks.associateBy(UploadedChunk::partIndex)
        if (uploaded.size != initialUploadedChunks.size) fail(UploadFailure.INVALID_STATE)
        uploaded.forEach { (partIndex, chunk) ->
            requireValidUploadedChunk(file, partIndex, chunk)
        }
        val expected = expectedChunks.associateBy(ExpectedUploadChunk::partIndex)
        if (
            expected.size != expectedChunks.size ||
            expected.size != file.chunkCount ||
            expected.keys != (0 until file.chunkCount).toSet()
        ) {
            fail(UploadFailure.INVALID_STATE)
        }

        val buffer = ByteArray(file.chunkSizeBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        var partIndex = 0
        var emittedAny = false
        var sentBytesThisRun = 0L
        var sendingElapsedMillis = 0L
        try {
            input.open(file.sourceUri).use { stream ->
                while (true) {
                    ensureActive()
                    val count = fillChunk(stream, buffer)
                    if (count == 0 && emittedAny) break
                    val plaintext = if (count == 0) byteArrayOf() else buffer.copyOf(count)
                    try {
                        val expectedChunk = requireNotNull(expected[partIndex])
                        if (
                            expectedChunk.plaintextSizeBytes != count.toLong() ||
                            expectedChunk.plaintextSha256 != Sha256.digest(plaintext)
                        ) {
                            fail(UploadFailure.SOURCE_CHANGED)
                        }
                        digest.update(plaintext)
                        val existing = uploaded[partIndex]
                        if (existing == null) {
                            val sendStartedAt = clock()
                            uploadChunk(file, partIndex, plaintext, dataKey)
                            sendingElapsedMillis += (clock() - sendStartedAt).coerceAtLeast(1)
                            sentBytesThisRun += count
                        }
                        emittedAny = true
                        totalBytes += count
                        partIndex += 1
                        val progress = UploadProgress(
                                completedBytes = totalBytes,
                                totalBytes = file.sizeBytes,
                                completedChunks = partIndex,
                                totalChunks = file.chunkCount,
                                speedBytesPerSecond = if (sendingElapsedMillis == 0L) {
                                    0
                                } else {
                                    sentBytesThisRun * 1_000 / sendingElapsedMillis
                                },
                            )
                        store.updateProgress(file.taskId, progress)
                        onProgress(progress)
                    } finally {
                        SecureErase.wipe(plaintext)
                    }
                    if (count < file.chunkSizeBytes) break
                }
            }
            val actualSha256 = digest.digest().toHex()
            if (
                totalBytes != file.sizeBytes ||
                partIndex != file.chunkCount ||
                actualSha256 != file.sha256
            ) {
                fail(UploadFailure.SOURCE_CHANGED)
            }
        } catch (error: UploadException) {
            throw error
        } catch (error: TelegramApiException) {
            throw error
        } catch (error: IOException) {
            throw UploadException(UploadFailure.SOURCE_UNAVAILABLE, error)
        } catch (error: SecurityException) {
            throw UploadException(UploadFailure.SOURCE_UNAVAILABLE, error)
        } finally {
            SecureErase.wipe(buffer)
        }
    }

    private suspend fun uploadChunk(
        file: UploadFileSnapshot,
        partIndex: Int,
        plaintext: ByteArray,
        dataKey: ByteArray,
    ) {
        val encrypted = cryptoEngine.encryptChunk(
            key = dataKey,
            plaintext = plaintext,
            associatedData = FileChunkAssociatedData.create(file.fileId, partIndex),
        )
        try {
            val nonce = cryptoEngine.extractNonce(encrypted)
            store.markChunkSending(
                file.taskId,
                SendingUploadChunk(partIndex, nonce, encrypted.size.toLong()),
            )
            try {
                withContext(NonCancellable) {
                    val remoteChunk = remote.sendChunk(
                        fileName = chunkFileName(file.fileId, partIndex),
                        encryptedBytes = encrypted,
                        partIndex = partIndex,
                    )
                    if (
                        remoteChunk.messageId <= 0 ||
                        remoteChunk.telegramFileId.isBlank() ||
                        remoteChunk.encryptedSizeBytes != encrypted.size.toLong()
                    ) {
                        fail(UploadFailure.INVALID_REMOTE_RESPONSE)
                    }
                    store.recordUploadedChunk(
                        file.taskId,
                        UploadedChunk(
                            partIndex = partIndex,
                            messageId = remoteChunk.messageId,
                            telegramFileId = remoteChunk.telegramFileId,
                            nonce = nonce,
                            encryptedSizeBytes = encrypted.size.toLong(),
                        ),
                    )
                }
            } catch (error: TelegramApiException) {
                if (error.failure is TelegramFailure.Api) {
                    try {
                        withContext(NonCancellable) {
                            store.discardSendingChunk(file.taskId, partIndex)
                        }
                    } catch (discardError: Exception) {
                        throw UploadException(UploadFailure.REMOTE_RESULT_UNKNOWN, discardError)
                    }
                    throw error
                }
                throw UploadException(UploadFailure.REMOTE_RESULT_UNKNOWN, error)
            } catch (error: Exception) {
                throw UploadException(UploadFailure.REMOTE_RESULT_UNKNOWN, error)
            }
        } finally {
            SecureErase.wipe(encrypted)
        }
    }

    private fun inspectSource(
        file: UploadFileSnapshot,
        ensureActive: () -> Unit,
    ): SourceManifest {
        val buffer = ByteArray(file.chunkSizeBytes)
        val fileDigest = MessageDigest.getInstance("SHA-256")
        val chunks = mutableListOf<ExpectedUploadChunk>()
        var sizeBytes = 0L
        var partIndex = 0
        var emittedAny = false
        try {
            input.open(file.sourceUri).use { stream ->
                while (true) {
                    ensureActive()
                    val count = fillChunk(stream, buffer)
                    if (count == 0 && emittedAny) break
                    fileDigest.update(buffer, 0, count)
                    val chunkDigest = MessageDigest.getInstance("SHA-256")
                    chunkDigest.update(buffer, 0, count)
                    chunks += ExpectedUploadChunk(
                        partIndex = partIndex,
                        plaintextSha256 = chunkDigest.digest().toHex(),
                        plaintextSizeBytes = count.toLong(),
                    )
                    emittedAny = true
                    sizeBytes += count
                    partIndex += 1
                    if (count < file.chunkSizeBytes) break
                }
            }
            return SourceManifest(fileDigest.digest().toHex(), sizeBytes, chunks)
        } catch (error: IOException) {
            throw UploadException(UploadFailure.SOURCE_UNAVAILABLE, error)
        } catch (error: SecurityException) {
            throw UploadException(UploadFailure.SOURCE_UNAVAILABLE, error)
        } finally {
            SecureErase.wipe(buffer)
        }
    }

    private fun requireValid(state: UploadResumeState) {
        val file = state.file
        if (
            file.taskId.isBlank() ||
            file.fileId.isBlank() ||
            file.originalName.isBlank() ||
            !file.sourceUri.startsWith("content://") ||
            file.sizeBytes < 0 ||
            file.chunkSizeBytes !in 1..MAX_PLAINTEXT_CHUNK_BYTES ||
            file.chunkCount != expectedChunkCount(file.sizeBytes, file.chunkSizeBytes)
        ) {
            fail(UploadFailure.INVALID_STATE)
        }
    }

    private fun requireValidUploadedChunk(
        file: UploadFileSnapshot,
        partIndex: Int,
        chunk: UploadedChunk,
    ) {
        val expectedPlaintext = plaintextSize(file, partIndex)
        if (
            partIndex !in 0 until file.chunkCount ||
            chunk.partIndex != partIndex ||
            chunk.messageId <= 0 ||
            chunk.telegramFileId.isBlank() ||
            chunk.nonce.size != NONCE_BYTES ||
            chunk.encryptedSizeBytes != expectedPlaintext + CryptoEngine.ENVELOPE_OVERHEAD_BYTES
        ) {
            fail(UploadFailure.INVALID_STATE)
        }
    }

    private fun plaintextSize(file: UploadFileSnapshot, partIndex: Int): Long {
        if (file.sizeBytes == 0L) return 0
        val offset = partIndex.toLong() * file.chunkSizeBytes
        return min(file.chunkSizeBytes.toLong(), file.sizeBytes - offset)
    }

    private fun expectedChunkCount(sizeBytes: Long, chunkSizeBytes: Int): Int {
        val count = if (sizeBytes == 0L) 1L else ((sizeBytes - 1) / chunkSizeBytes) + 1
        if (count > Int.MAX_VALUE) fail(UploadFailure.INVALID_STATE)
        return count.toInt()
    }

    private fun fillChunk(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            when {
                count < 0 -> return offset
                count > 0 -> offset += count
                else -> {
                    val next = input.read()
                    if (next < 0) return offset
                    buffer[offset++] = next.toByte()
                }
            }
        }
        return offset
    }

    private fun chunkFileName(fileId: String, partIndex: Int): String =
        "td_${fileId}_${partIndex.toString().padStart(PART_WIDTH, '0')}.bin"

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun fail(failure: UploadFailure): Nothing = throw UploadException(failure)

    private data class SourceManifest(
        val sha256: String,
        val sizeBytes: Long,
        val chunks: List<ExpectedUploadChunk>,
    )

    private companion object {
        const val PART_WIDTH = 6
        const val NONCE_BYTES = 12
        const val MAX_PLAINTEXT_CHUNK_BYTES = 20_000_000 - CryptoEngine.ENVELOPE_OVERHEAD_BYTES - 1
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

object FileChunkAssociatedData {
    fun create(fileId: String, partIndex: Int): ByteArray {
        require(fileId.isNotBlank() && partIndex >= 0)
        return "teledrive.file-chunk.v1\u0000$fileId\u0000$partIndex".encodeToByteArray()
    }
}
