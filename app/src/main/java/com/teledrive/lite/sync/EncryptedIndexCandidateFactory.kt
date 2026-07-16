package com.teledrive.lite.sync

import androidx.room.withTransaction
import com.teledrive.lite.crypto.CloudIndexEnvelopeCryptor
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.index.CloudIndexPayload
import com.teledrive.lite.index.CloudIndexPayloadMapper
import com.teledrive.lite.index.CloudIndexPointer
import com.teledrive.lite.index.EncryptedIndexCodec
import com.teledrive.lite.model.PendingOperationType
import com.teledrive.lite.repository.CloudCacheSnapshot
import com.teledrive.lite.settings.SetupCryptoContext
import com.teledrive.lite.util.SecureErase
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

interface IndexSnapshotSource {
    suspend fun read(includedOperationIds: Set<String>): CloudCacheSnapshot
}

class RoomIndexSnapshotSource(
    private val database: TeleDriveDatabase,
) : IndexSnapshotSource {
    override suspend fun read(includedOperationIds: Set<String>): CloudCacheSnapshot =
        database.withTransaction {
            val operations = database.pendingOperationDao().getAll()
                .filter { it.type != PendingOperationType.INDEX_UPDATE }
            if (operations.map { it.id }.toSet() != includedOperationIds) {
                fail(IndexCandidateFailure.SNAPSHOT_CHANGED)
            }
            CloudCacheSnapshot(
                folders = database.folderDao().getAll(),
                files = database.fileDao().getAll(),
                chunks = database.chunkDao().getAll(),
                indexState = requireNotNull(
                    database.indexStateDao().get(com.teledrive.lite.database.IndexStateEntity.SINGLETON_ID),
                ),
                pendingOperations = operations,
            )
        }

    private fun fail(failure: IndexCandidateFailure): Nothing = throw IndexCandidateException(failure)
}

interface IndexCandidateArtifactStore {
    fun load(operationId: String): ByteArray?
    fun save(operationId: String, bytes: ByteArray)
    fun delete(operationId: String)
}

class FileIndexCandidateArtifactStore(
    private val directory: File,
) : IndexCandidateArtifactStore {
    override fun load(operationId: String): ByteArray? {
        val file = target(operationId)
        if (!file.exists()) return null
        require(file.isFile && file.length() in 1..CloudIndexEnvelopeCryptor.MAX_ENVELOPE_BYTES.toLong())
        return file.readBytes()
    }

    override fun save(operationId: String, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= CloudIndexEnvelopeCryptor.MAX_ENVELOPE_BYTES)
        require(directory.exists() || directory.mkdirs())
        val target = target(operationId)
        require(!target.exists()) { "Candidate artifact already exists" }
        val temporary = File.createTempFile("index-candidate-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                require(temporary.renameTo(target)) { "Unable to persist candidate artifact" }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override fun delete(operationId: String) {
        val file = target(operationId)
        if (file.exists() && !file.delete()) error("Unable to delete candidate artifact")
    }

    private fun target(operationId: String): File {
        require(operationId.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256").digest(operationId.encodeToByteArray())
        val name = digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
        digest.fill(0)
        return File(directory, "$name.bin")
    }
}

enum class IndexCandidateFailure {
    CRYPTO_CONTEXT_MISSING,
    SNAPSHOT_CHANGED,
    INVALID_SNAPSHOT,
    ARTIFACT_MISMATCH,
}

class IndexCandidateException(
    val failure: IndexCandidateFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.name, cause)

class EncryptedIndexCandidateFactory(
    private val snapshotSource: IndexSnapshotSource,
    private val cryptoContextProvider: () -> SetupCryptoContext?,
    private val encryptedIndexCodec: EncryptedIndexCodec,
    private val artifactStore: IndexCandidateArtifactStore,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : IndexCandidateFactory {
    override suspend fun prepare(request: IndexCandidatePreparationRequest) {
        val preparedKey = preparationKey(request.operationId)
        val context = cryptoContextProvider()
            ?: fail(IndexCandidateFailure.CRYPTO_CONTEXT_MISSING)
        context.use {
            val cached = artifactStore.load(preparedKey)
            if (cached != null) {
                try {
                    val payload = decrypt(cached, context)
                    requireMatchesPreparation(payload, request)
                    return
                } catch (error: Exception) {
                    throw IndexCandidateException(IndexCandidateFailure.ARTIFACT_MISMATCH, error)
                } finally {
                    SecureErase.wipe(cached)
                }
            }

            val snapshot = try {
                snapshotSource.read(request.includedOperationIds)
            } catch (error: IndexCandidateException) {
                throw error
            } catch (error: Exception) {
                throw IndexCandidateException(IndexCandidateFailure.SNAPSHOT_CHANGED, error)
            }
            requireStableSnapshot(
                snapshot = snapshot,
                revision = request.revision,
                previousMessageId = request.previousIndexMessageId,
                previousFileId = request.previousIndexFileId,
                includedOperationIds = request.includedOperationIds,
            )
            val payload = buildPayload(
                snapshot = snapshot,
                revision = request.revision,
                currentMessageId = PREPARED_MESSAGE_ID,
                previousMessageId = request.previousIndexMessageId,
                previousFileId = request.previousIndexFileId,
                context = context,
            )
            val encrypted = encrypt(payload, context)
            try {
                artifactStore.save(preparedKey, encrypted)
            } finally {
                SecureErase.wipe(encrypted)
            }
        }
    }

    override suspend fun create(request: IndexCandidateRequest): IndexCandidate {
        val context = cryptoContextProvider()
            ?: fail(IndexCandidateFailure.CRYPTO_CONTEXT_MISSING)
        context.use {
            val cached = artifactStore.load(request.operationId)
            if (cached != null) {
                return try {
                    val payload = decrypt(cached, context)
                    requireMatches(payload, request)
                    IndexCandidate(
                        revision = request.revision,
                        previousIndexMessageId = request.previousIndexMessageId,
                        messageId = request.messageId,
                        fileName = IndexAtomicUpdater.INDEX_FILE_NAME,
                        content = cached,
                        indexedFileIds = payload.files.map { it.id }.toSet(),
                    )
                } catch (error: Exception) {
                    throw IndexCandidateException(IndexCandidateFailure.ARTIFACT_MISMATCH, error)
                } finally {
                    SecureErase.wipe(cached)
                }
            }

            val preparedKey = preparationKey(request.operationId)
            prepare(
                IndexCandidatePreparationRequest(
                    operationId = request.operationId,
                    revision = request.revision,
                    previousIndexMessageId = request.previousIndexMessageId,
                    previousIndexFileId = request.previousIndexFileId,
                    includedOperationIds = request.includedOperationIds,
                ),
            )
            val prepared = artifactStore.load(preparedKey)
                ?: fail(IndexCandidateFailure.ARTIFACT_MISMATCH)
            val template = try {
                decrypt(prepared, context).also { payload ->
                    requireMatchesPreparation(
                        payload,
                        IndexCandidatePreparationRequest(
                            operationId = request.operationId,
                            revision = request.revision,
                            previousIndexMessageId = request.previousIndexMessageId,
                            previousIndexFileId = request.previousIndexFileId,
                            includedOperationIds = request.includedOperationIds,
                        ),
                    )
                }
            } catch (error: Exception) {
                throw IndexCandidateException(IndexCandidateFailure.ARTIFACT_MISMATCH, error)
            } finally {
                SecureErase.wipe(prepared)
            }
            val finalPayload = template.copy(currentIndexMessageId = request.messageId)
            val encrypted = encrypt(finalPayload, context)
            return try {
                artifactStore.save(request.operationId, encrypted)
                artifactStore.delete(preparedKey)
                IndexCandidate(
                    revision = request.revision,
                    previousIndexMessageId = request.previousIndexMessageId,
                    messageId = request.messageId,
                    fileName = IndexAtomicUpdater.INDEX_FILE_NAME,
                    content = encrypted,
                    indexedFileIds = finalPayload.files.map { it.id }.toSet(),
                )
            } finally {
                SecureErase.wipe(encrypted)
            }
        }
    }

    override suspend fun clear(operationId: String) {
        artifactStore.delete(operationId)
        artifactStore.delete(preparationKey(operationId))
    }

    private fun buildPayload(
        snapshot: CloudCacheSnapshot,
        revision: Long,
        currentMessageId: Long,
        previousMessageId: Long?,
        previousFileId: String?,
        context: SetupCryptoContext,
    ): CloudIndexPayload {
        val previous = if (revision == 1L) {
            null
        } else {
            CloudIndexPointer(
                revision = revision - 1,
                messageId = previousMessageId ?: fail(IndexCandidateFailure.INVALID_SNAPSHOT),
                fileId = previousFileId ?: fail(IndexCandidateFailure.INVALID_SNAPSHOT),
            )
        }
        return try {
            CloudIndexPayloadMapper.toPayload(
                snapshot = snapshot,
                appVersion = appVersion,
                revision = revision,
                currentIndexMessageId = currentMessageId,
                previous = previous,
                createdAtEpochMillis = snapshot.folders
                    .single { it.parentId == null }.createdAtEpochMillis,
                updatedAtEpochMillis = clock(),
                keyDerivation = context.keyDerivation,
            )
        } catch (error: Exception) {
            throw IndexCandidateException(IndexCandidateFailure.INVALID_SNAPSHOT, error)
        }
    }

    private fun encrypt(payload: CloudIndexPayload, context: SetupCryptoContext): ByteArray =
        context.withMasterKey { masterKey ->
            encryptedIndexCodec.encrypt(payload, masterKey, context.keyDerivation)
        }

    private fun decrypt(bytes: ByteArray, context: SetupCryptoContext): CloudIndexPayload =
        context.withMasterKey { masterKey ->
            encryptedIndexCodec.decryptWithMasterKey(bytes, masterKey, context.keyDerivation)
        }

    private fun requireStableSnapshot(
        snapshot: CloudCacheSnapshot,
        revision: Long,
        previousMessageId: Long?,
        previousFileId: String?,
        includedOperationIds: Set<String>,
    ) {
        val state = snapshot.indexState
        if (
            state.revision != revision - 1 ||
            state.currentIndexMessageId != previousMessageId ||
            state.currentIndexFileId != previousFileId ||
            snapshot.pendingOperations.map { it.id }.toSet() != includedOperationIds
        ) {
            fail(IndexCandidateFailure.SNAPSHOT_CHANGED)
        }
    }

    private fun requireMatchesPreparation(
        payload: CloudIndexPayload,
        request: IndexCandidatePreparationRequest,
    ) {
        if (
            payload.revision != request.revision ||
            payload.currentIndexMessageId != PREPARED_MESSAGE_ID ||
            payload.previousIndexMessageId != request.previousIndexMessageId ||
            payload.previous?.fileId != request.previousIndexFileId ||
            payload.pendingOperations.map { it.id }.toSet() != request.includedOperationIds
        ) {
            fail(IndexCandidateFailure.ARTIFACT_MISMATCH)
        }
    }

    private fun requireMatches(payload: CloudIndexPayload, request: IndexCandidateRequest) {
        if (
            payload.revision != request.revision ||
            payload.currentIndexMessageId != request.messageId ||
            payload.previousIndexMessageId != request.previousIndexMessageId ||
            payload.previous?.fileId != request.previousIndexFileId ||
            payload.pendingOperations.map { it.id }.toSet() != request.includedOperationIds
        ) {
            fail(IndexCandidateFailure.ARTIFACT_MISMATCH)
        }
    }

    private fun fail(failure: IndexCandidateFailure): Nothing = throw IndexCandidateException(failure)

    private fun preparationKey(operationId: String): String = "$operationId:prepared"

    private companion object {
        const val PREPARED_MESSAGE_ID = Long.MAX_VALUE
    }
}
