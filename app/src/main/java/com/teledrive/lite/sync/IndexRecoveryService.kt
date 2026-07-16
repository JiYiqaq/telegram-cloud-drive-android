package com.teledrive.lite.sync

import com.teledrive.lite.crypto.UnsupportedCloudIndexEnvelopeVersionException
import com.teledrive.lite.crypto.UnsupportedCloudIndexKdfException
import com.teledrive.lite.crypto.KeyDerivation
import com.teledrive.lite.crypto.KeyDerivationParameters
import com.teledrive.lite.index.CloudIndexPayloadMapper
import com.teledrive.lite.index.EncryptedIndexCodec
import com.teledrive.lite.index.IndexFormatException
import com.teledrive.lite.index.IndexFormatFailure
import com.teledrive.lite.repository.CloudCacheSnapshot
import com.teledrive.lite.repository.FileRepository
import com.teledrive.lite.util.SecureErase

interface IndexCacheReplacer {
    suspend fun replace(snapshot: CloudCacheSnapshot)
}

fun interface RecoveryContextCommitter {
    fun commit(parameters: KeyDerivationParameters, masterKey: ByteArray)
}

class FileRepositoryIndexCacheReplacer(
    private val repository: FileRepository,
) : IndexCacheReplacer {
    override suspend fun replace(snapshot: CloudCacheSnapshot) {
        repository.replaceCloudCache(snapshot)
    }
}

sealed interface IndexRecoveryOutcome {
    data class Recovered(
        val revision: Long,
        val messageId: Long,
        val fileId: String,
    ) : IndexRecoveryOutcome
}

enum class IndexRecoveryFailure {
    NO_PINNED_INDEX,
    DOWNLOAD_FAILED,
    DECRYPTION_FAILED,
    UNSUPPORTED_INDEX,
    INVALID_PAYLOAD,
    PAYLOAD_POINTER_MISMATCH,
    PIN_CHANGED_DURING_RECOVERY,
    LOCAL_CACHE_REJECTED,
    CONTEXT_COMMIT_FAILED,
}

class IndexRecoveryException(
    val failure: IndexRecoveryFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.name, cause)

class IndexRecoveryService(
    private val remote: IndexRecoveryRemote,
    private val encryptedIndexCodec: EncryptedIndexCodec,
    private val cacheReplacer: IndexCacheReplacer,
    private val contextCommitter: RecoveryContextCommitter? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun recover(password: CharArray): IndexRecoveryOutcome {
        val passwordCopy = password.copyOf()
        var envelope: ByteArray? = null
        try {
            val pinned = remote.getPinned()
                ?: fail(IndexRecoveryFailure.NO_PINNED_INDEX)
            envelope = try {
                remote.download(pinned)
            } catch (error: Exception) {
                throw IndexRecoveryException(IndexRecoveryFailure.DOWNLOAD_FAILED, error)
            }
            val payload = try {
                encryptedIndexCodec.decryptWithPassword(envelope, passwordCopy)
            } catch (error: UnsupportedCloudIndexEnvelopeVersionException) {
                throw IndexRecoveryException(IndexRecoveryFailure.UNSUPPORTED_INDEX, error)
            } catch (error: UnsupportedCloudIndexKdfException) {
                throw IndexRecoveryException(IndexRecoveryFailure.UNSUPPORTED_INDEX, error)
            } catch (error: IndexFormatException) {
                if (
                    error.failure == IndexFormatFailure.UNSUPPORTED_SCHEMA ||
                    error.failure == IndexFormatFailure.UNSUPPORTED_VERSION
                ) {
                    throw IndexRecoveryException(IndexRecoveryFailure.UNSUPPORTED_INDEX, error)
                }
                throw IndexRecoveryException(IndexRecoveryFailure.INVALID_PAYLOAD, error)
            } catch (error: Exception) {
                throw IndexRecoveryException(IndexRecoveryFailure.DECRYPTION_FAILED, error)
            }

            if (payload.currentIndexMessageId != pinned.messageId) {
                fail(IndexRecoveryFailure.PAYLOAD_POINTER_MISMATCH)
            }
            val snapshot = try {
                CloudIndexPayloadMapper.toCloudCacheSnapshot(
                    payload = payload,
                    currentIndexFileId = pinned.fileId,
                    syncedAtEpochMillis = clock(),
                )
            } catch (error: Exception) {
                throw IndexRecoveryException(IndexRecoveryFailure.INVALID_PAYLOAD, error)
            }

            if (remote.getPinned() != pinned) {
                fail(IndexRecoveryFailure.PIN_CHANGED_DURING_RECOVERY)
            }
            contextCommitter?.let { committer ->
                val salt = payload.keyDerivation.salt.toByteArray()
                var masterKey: ByteArray? = null
                try {
                    val parameters = KeyDerivationParameters.pbkdf2(
                        salt = salt,
                        iterations = payload.keyDerivation.iterations,
                        keyLengthBytes = payload.keyDerivation.keyLengthBytes,
                    )
                    if (payload.keyDerivation.algorithm != parameters.algorithm) {
                        fail(IndexRecoveryFailure.UNSUPPORTED_INDEX)
                    }
                    masterKey = KeyDerivation.derive(passwordCopy, parameters)
                    committer.commit(parameters, masterKey)
                } catch (error: IndexRecoveryException) {
                    throw error
                } catch (error: Exception) {
                    throw IndexRecoveryException(IndexRecoveryFailure.CONTEXT_COMMIT_FAILED, error)
                } finally {
                    SecureErase.wipe(salt)
                    masterKey?.let(SecureErase::wipe)
                }
            }
            try {
                cacheReplacer.replace(snapshot)
            } catch (error: Exception) {
                throw IndexRecoveryException(IndexRecoveryFailure.LOCAL_CACHE_REJECTED, error)
            }
            return IndexRecoveryOutcome.Recovered(
                revision = payload.revision,
                messageId = pinned.messageId,
                fileId = pinned.fileId,
            )
        } finally {
            SecureErase.wipe(passwordCopy)
            envelope?.let(SecureErase::wipe)
        }
    }

    private fun fail(failure: IndexRecoveryFailure): Nothing = throw IndexRecoveryException(failure)
}
