package com.teledrive.lite.download

import com.teledrive.lite.crypto.CryptoAuthenticationException
import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.crypto.CryptoFormatException
import com.teledrive.lite.crypto.KeyWrapping
import com.teledrive.lite.settings.SetupCryptoContext
import com.teledrive.lite.telegram.TelegramCloudLimits
import com.teledrive.lite.upload.FileChunkAssociatedData
import com.teledrive.lite.util.SecureErase
import java.security.MessageDigest
import kotlin.math.min

data class DownloadChunkSnapshot(
    val partIndex: Int,
    val telegramFileId: String,
    val nonce: ByteArray,
    val encryptedSizeBytes: Long,
)

data class DownloadFileSnapshot(
    val taskId: String,
    val fileId: String,
    val fileName: String,
    val destinationUri: String,
    val sizeBytes: Long,
    val chunkSizeBytes: Int,
    val chunkCount: Int,
    val sha256: String,
    val wrappedDataKey: ByteArray,
    val chunks: List<DownloadChunkSnapshot>,
)

data class DownloadProgress(
    val completedBytes: Long,
    val totalBytes: Long,
    val completedChunks: Int,
    val totalChunks: Int,
    val speedBytesPerSecond: Long,
)

sealed interface DownloadOutcome {
    data class Completed(val fileId: String, val sizeBytes: Long) : DownloadOutcome
}

interface DownloadStore {
    suspend fun load(taskId: String): DownloadFileSnapshot
    suspend fun updateProgress(taskId: String, progress: DownloadProgress)
    suspend fun finalizeAfterDestination(taskId: String)
}

interface DownloadRemote {
    suspend fun downloadChunk(
        telegramFileId: String,
        expectedEncryptedSizeBytes: Long,
    ): ByteArray
}

interface DownloadDestination {
    fun open(destinationUri: String): DownloadOutput
}

interface DownloadOutput {
    fun write(plaintext: ByteArray)
    fun commit()
    fun abort(removeDestination: Boolean)
}

enum class DownloadFailure {
    INVALID_STATE,
    CRYPTO_CONTEXT_MISSING,
    AUTHENTICATION_FAILED,
    REMOTE_SIZE_MISMATCH,
    INTEGRITY_FAILED,
    DESTINATION_UNAVAILABLE,
}

class DownloadException(
    val failure: DownloadFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.name, cause)

class DownloadCoordinator(
    private val store: DownloadStore,
    private val remote: DownloadRemote,
    private val destination: DownloadDestination,
    private val cryptoEngine: CryptoEngine,
    private val keyWrapping: KeyWrapping,
    private val cryptoContextProvider: () -> SetupCryptoContext?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        taskId: String,
        ensureActive: () -> Unit = {},
        onProgress: suspend (DownloadProgress) -> Unit = {},
    ): DownloadOutcome.Completed {
        val file = store.load(taskId)
        requireValid(file)
        val context = cryptoContextProvider()
            ?: fail(DownloadFailure.CRYPTO_CONTEXT_MISSING)
        context.use {
            val wrapped = file.wrappedDataKey.copyOf()
            val dataKey = try {
                context.withMasterKey { masterKey ->
                    keyWrapping.unwrap(file.fileId, masterKey, wrapped)
                }
            } catch (error: Exception) {
                throw DownloadException(DownloadFailure.AUTHENTICATION_FAILED, error)
            } finally {
                SecureErase.wipe(wrapped)
            }
            try {
                return downloadToDestination(file, dataKey, ensureActive, onProgress)
            } finally {
                SecureErase.wipe(dataKey)
            }
        }
    }

    private suspend fun downloadToDestination(
        file: DownloadFileSnapshot,
        dataKey: ByteArray,
        ensureActive: () -> Unit,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadOutcome.Completed {
        val output = destination.open(file.destinationUri)
        val digest = MessageDigest.getInstance("SHA-256")
        val orderedChunks = file.chunks.sortedBy(DownloadChunkSnapshot::partIndex)
        var completedBytes = 0L
        var completedChunks = 0
        var downloadedBytesThisRun = 0L
        var downloadElapsedMillis = 0L
        var destinationCommitted = false
        try {
            orderedChunks.forEach { chunk ->
                ensureActive()
                val startedAt = clock()
                val encrypted = remote.downloadChunk(
                    chunk.telegramFileId,
                    chunk.encryptedSizeBytes,
                )
                downloadElapsedMillis += (clock() - startedAt).coerceAtLeast(1)
                downloadedBytesThisRun += encrypted.size
                if (encrypted.size.toLong() != chunk.encryptedSizeBytes) {
                    SecureErase.wipe(encrypted)
                    fail(DownloadFailure.REMOTE_SIZE_MISMATCH)
                }
                val plaintext = try {
                    val nonce = cryptoEngine.extractNonce(encrypted)
                    if (!nonce.contentEquals(chunk.nonce)) {
                        fail(DownloadFailure.AUTHENTICATION_FAILED)
                    }
                    cryptoEngine.decryptChunk(
                        dataKey,
                        encrypted,
                        FileChunkAssociatedData.create(file.fileId, chunk.partIndex),
                    )
                } catch (error: DownloadException) {
                    throw error
                } catch (error: CryptoAuthenticationException) {
                    throw DownloadException(DownloadFailure.AUTHENTICATION_FAILED, error)
                } catch (error: CryptoFormatException) {
                    throw DownloadException(DownloadFailure.AUTHENTICATION_FAILED, error)
                } finally {
                    SecureErase.wipe(encrypted)
                }
                try {
                    val expectedPlaintextSize = plaintextSize(file, chunk.partIndex)
                    if (plaintext.size.toLong() != expectedPlaintextSize) {
                        fail(DownloadFailure.INTEGRITY_FAILED)
                    }
                    output.write(plaintext)
                    digest.update(plaintext)
                    completedBytes += plaintext.size
                    completedChunks += 1
                    val progress = DownloadProgress(
                        completedBytes = completedBytes,
                        totalBytes = file.sizeBytes,
                        completedChunks = completedChunks,
                        totalChunks = file.chunkCount,
                        speedBytesPerSecond = downloadedBytesThisRun * 1_000 / downloadElapsedMillis,
                    )
                    store.updateProgress(file.taskId, progress)
                    onProgress(progress)
                } finally {
                    SecureErase.wipe(plaintext)
                }
            }
            if (
                completedBytes != file.sizeBytes ||
                completedChunks != file.chunkCount ||
                digest.digest().toHex() != file.sha256
            ) {
                fail(DownloadFailure.INTEGRITY_FAILED)
            }
            output.commit()
            destinationCommitted = true
            store.finalizeAfterDestination(file.taskId)
            return DownloadOutcome.Completed(file.fileId, file.sizeBytes)
        } catch (error: Exception) {
            if (!destinationCommitted) {
                val removeDestination = error is DownloadException && error.failure in setOf(
                    DownloadFailure.AUTHENTICATION_FAILED,
                    DownloadFailure.REMOTE_SIZE_MISMATCH,
                    DownloadFailure.INTEGRITY_FAILED,
                )
                runCatching {
                    output.abort(removeDestination)
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }

    private fun requireValid(file: DownloadFileSnapshot) {
        val expectedChunkCount = if (file.sizeBytes == 0L) {
            1L
        } else if (file.sizeBytes > 0 && file.chunkSizeBytes > 0) {
            ((file.sizeBytes - 1) / file.chunkSizeBytes) + 1
        } else {
            -1L
        }
        val ordered = file.chunks.sortedBy(DownloadChunkSnapshot::partIndex)
        if (
            file.taskId.isBlank() ||
            file.fileId.isBlank() ||
            file.fileName.isBlank() ||
            !file.destinationUri.startsWith("content://") ||
            file.sizeBytes < 0 ||
            file.chunkSizeBytes <= 0 ||
            expectedChunkCount > Int.MAX_VALUE ||
            file.chunkCount != expectedChunkCount.toInt() ||
            !SHA_256.matches(file.sha256) ||
            file.wrappedDataKey.size != CryptoEngine.DATA_KEY_BYTES + CryptoEngine.ENVELOPE_OVERHEAD_BYTES ||
            ordered.size != file.chunkCount
        ) {
            fail(DownloadFailure.INVALID_STATE)
        }
        ordered.forEachIndexed { index, chunk ->
            val expectedEncryptedSize = plaintextSize(file, index) + CryptoEngine.ENVELOPE_OVERHEAD_BYTES
            if (
                chunk.partIndex != index ||
                chunk.telegramFileId.isBlank() ||
                chunk.nonce.size != NONCE_BYTES ||
                chunk.encryptedSizeBytes != expectedEncryptedSize ||
                !TelegramCloudLimits.isEncryptedChunkSizeSafe(chunk.encryptedSizeBytes)
            ) {
                fail(DownloadFailure.INVALID_STATE)
            }
        }
    }

    private fun plaintextSize(file: DownloadFileSnapshot, partIndex: Int): Long {
        if (file.sizeBytes == 0L) return 0
        val offset = partIndex.toLong() * file.chunkSizeBytes
        return min(file.chunkSizeBytes.toLong(), file.sizeBytes - offset)
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun fail(failure: DownloadFailure): Nothing = throw DownloadException(failure)

    private companion object {
        const val NONCE_BYTES = 12
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}
