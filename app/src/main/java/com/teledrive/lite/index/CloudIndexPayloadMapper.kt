package com.teledrive.lite.index

import com.teledrive.lite.crypto.KeyDerivationParameters
import com.teledrive.lite.database.ChunkEntity
import com.teledrive.lite.database.FileEntity
import com.teledrive.lite.database.FolderEntity
import com.teledrive.lite.database.IndexStateEntity
import com.teledrive.lite.database.PendingOperationEntity
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.PendingOperationStatus
import com.teledrive.lite.model.PendingOperationType
import com.teledrive.lite.repository.CloudCacheSnapshot
import com.teledrive.lite.repository.CloudSnapshotValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

object CloudIndexPayloadMapper {
    fun toPayload(
        snapshot: CloudCacheSnapshot,
        appVersion: String,
        revision: Long,
        currentIndexMessageId: Long,
        previous: CloudIndexPointer?,
        createdAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
        keyDerivation: KeyDerivationParameters,
    ): CloudIndexPayload {
        val chunksByFile = snapshot.chunks.groupBy(ChunkEntity::fileId)
        val publishedFiles = snapshot.files.filter { file ->
            file.isCloudIndexed || isCompleteUploadCandidate(file, chunksByFile[file.id].orEmpty())
        }
        val publishedFileIds = publishedFiles.map(FileEntity::id).toSet()
        val payload = CloudIndexPayload(
            schema = CloudIndexPayload.SCHEMA,
            formatVersion = CloudIndexPayload.CURRENT_FORMAT_VERSION,
            appVersion = appVersion,
            revision = revision,
            currentIndexMessageId = currentIndexMessageId,
            previous = previous,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
            rootFolderId = snapshot.indexState.rootFolderId,
            keyDerivation = IndexKeyDerivationMetadata(
                algorithm = keyDerivation.algorithm,
                salt = IndexBytes.of(keyDerivation.salt),
                iterations = keyDerivation.iterations,
                keyLengthBytes = keyDerivation.keyLengthBytes,
            ),
            encryptionParameters = IndexEncryptionParameters(
                index = AES_GCM_PARAMETERS,
                file = AES_GCM_PARAMETERS,
            ),
            folders = snapshot.folders.map { it.toIndexFolder() },
            files = publishedFiles.map { file ->
                file.toIndexFile(candidateUploadedAtEpochMillis = updatedAtEpochMillis)
            },
            chunks = snapshot.chunks
                .filter { it.fileId in publishedFileIds }
                .map { it.toIndexChunk() },
            pendingOperations = snapshot.pendingOperations
                .filter { it.type != PendingOperationType.INDEX_UPDATE }
                .map { it.toIndexPendingOperation() },
        )
        IndexValidator.requireValid(payload)
        return payload
    }

    fun toCloudCacheSnapshot(
        payload: CloudIndexPayload,
        currentIndexFileId: String,
        syncedAtEpochMillis: Long,
    ): CloudCacheSnapshot {
        IndexValidator.requireValid(payload)
        require(currentIndexFileId.isNotBlank())
        require(syncedAtEpochMillis >= 0)
        val snapshot = CloudCacheSnapshot(
            folders = payload.folders.map { it.toEntity() },
            files = payload.files.map { it.toEntity() },
            chunks = payload.chunks.map { it.toEntity() },
            indexState = IndexStateEntity(
                id = IndexStateEntity.SINGLETON_ID,
                formatVersion = payload.formatVersion,
                revision = payload.revision,
                rootFolderId = payload.rootFolderId,
                currentIndexMessageId = payload.currentIndexMessageId,
                previousIndexMessageId = payload.previousIndexMessageId,
                currentIndexFileId = currentIndexFileId,
                lastSyncedAtEpochMillis = syncedAtEpochMillis,
                syncStatus = IndexSyncStatus.SYNCED,
            ),
            pendingOperations = payload.pendingOperations.map { it.toEntity() },
        )
        CloudSnapshotValidator.requireValid(snapshot)
        return snapshot
    }

    private fun FolderEntity.toIndexFolder() = IndexFolder(
        id = id,
        name = name,
        parentId = parentId,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun FileEntity.toIndexFile(candidateUploadedAtEpochMillis: Long) = IndexFile(
        id = id,
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        createdAtEpochMillis = createdAtEpochMillis,
        modifiedAtEpochMillis = modifiedAtEpochMillis,
        uploadedAtEpochMillis = uploadedAtEpochMillis ?: candidateUploadedAtEpochMillis,
        parentFolderId = parentFolderId,
        sha256 = requireNotNull(sha256),
        encryptionFormatVersion = encryptionFormatVersion,
        chunkSizeBytes = chunkSizeBytes,
        chunkCount = chunkCount,
        wrappedDataKey = IndexBytes.of(requireNotNull(wrappedDataKey)),
        status = if (isCloudIndexed) status.toIndexStatus() else IndexFileStatus.AVAILABLE,
        isCloudIndexed = true,
    )

    private fun ChunkEntity.toIndexChunk() = IndexChunk(
        id = id,
        fileId = fileId,
        partIndex = partIndex,
        messageId = requireNotNull(messageId),
        telegramFileId = requireNotNull(telegramFileId),
        nonce = IndexBytes.of(requireNotNull(nonce)),
        encryptedSizeBytes = encryptedSizeBytes,
        uploadStatus = uploadStatus.toIndexStatus(),
    )

    private fun PendingOperationEntity.toIndexPendingOperation() = IndexPendingOperation(
        id = id,
        type = type.toIndexType(),
        targetId = targetId,
        payload = payloadJson?.let(::parseStringMap),
        remainingMessageIds = remainingMessageIdsJson?.let(::parseLongList),
        baseRevision = requireNotNull(baseRevision),
        candidateRevision = candidateRevision,
        indexConfirmedAtEpochMillis = indexConfirmedAtEpochMillis,
        status = status.toIndexStatus(),
        attempt = attempt,
        nextRetryAtEpochMillis = nextRetryAtEpochMillis,
        errorCode = errorCode,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun IndexFolder.toEntity() = FolderEntity(
        id = id,
        name = name,
        parentId = parentId,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun IndexFile.toEntity() = FileEntity(
        id = id,
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        createdAtEpochMillis = createdAtEpochMillis,
        modifiedAtEpochMillis = modifiedAtEpochMillis,
        uploadedAtEpochMillis = uploadedAtEpochMillis,
        parentFolderId = parentFolderId,
        sha256 = sha256,
        encryptionFormatVersion = encryptionFormatVersion,
        chunkSizeBytes = chunkSizeBytes,
        chunkCount = chunkCount,
        wrappedDataKey = wrappedDataKey.toByteArray(),
        status = status.toEntityStatus(),
        isCloudIndexed = isCloudIndexed,
    )

    private fun IndexChunk.toEntity() = ChunkEntity(
        id = id,
        fileId = fileId,
        partIndex = partIndex,
        messageId = messageId,
        telegramFileId = telegramFileId,
        nonce = nonce.toByteArray(),
        encryptedSizeBytes = encryptedSizeBytes,
        uploadStatus = uploadStatus.toEntityStatus(),
    )

    private fun IndexPendingOperation.toEntity() = PendingOperationEntity(
        id = id,
        type = type.toEntityType(),
        targetId = targetId,
        payloadJson = payload?.let(::stringMapJson),
        remainingMessageIdsJson = remainingMessageIds?.let(::longListJson),
        baseRevision = baseRevision,
        candidateRevision = candidateRevision,
        indexConfirmedAtEpochMillis = indexConfirmedAtEpochMillis,
        status = status.toEntityStatus(),
        attempt = attempt,
        nextRetryAtEpochMillis = nextRetryAtEpochMillis,
        errorCode = errorCode,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun FileStatus.toIndexStatus(): IndexFileStatus = when (this) {
        FileStatus.AVAILABLE -> IndexFileStatus.AVAILABLE
        FileStatus.DELETING -> IndexFileStatus.DELETING
        FileStatus.PARTIALLY_DELETED -> IndexFileStatus.PARTIALLY_DELETED
        else -> error("File state is not publishable: $this")
    }

    private fun IndexFileStatus.toEntityStatus(): FileStatus = when (this) {
        IndexFileStatus.AVAILABLE -> FileStatus.AVAILABLE
        IndexFileStatus.DELETING -> FileStatus.DELETING
        IndexFileStatus.PARTIALLY_DELETED -> FileStatus.PARTIALLY_DELETED
    }

    private fun ChunkUploadStatus.toIndexStatus(): IndexChunkStatus = when (this) {
        ChunkUploadStatus.UPLOADED -> IndexChunkStatus.UPLOADED
        ChunkUploadStatus.DELETING -> IndexChunkStatus.DELETING
        ChunkUploadStatus.DELETED -> IndexChunkStatus.DELETED
        ChunkUploadStatus.FAILED -> IndexChunkStatus.FAILED
        else -> error("Chunk state is not publishable: $this")
    }

    private fun IndexChunkStatus.toEntityStatus(): ChunkUploadStatus = when (this) {
        IndexChunkStatus.UPLOADED -> ChunkUploadStatus.UPLOADED
        IndexChunkStatus.DELETING -> ChunkUploadStatus.DELETING
        IndexChunkStatus.DELETED -> ChunkUploadStatus.DELETED
        IndexChunkStatus.FAILED -> ChunkUploadStatus.FAILED
    }

    private fun PendingOperationType.toIndexType(): IndexPendingOperationType = when (this) {
        PendingOperationType.CREATE_FOLDER -> IndexPendingOperationType.CREATE_FOLDER
        PendingOperationType.RENAME -> IndexPendingOperationType.RENAME
        PendingOperationType.MOVE -> IndexPendingOperationType.MOVE
        PendingOperationType.DELETE_FOLDER -> IndexPendingOperationType.DELETE_FOLDER
        PendingOperationType.DELETE -> IndexPendingOperationType.DELETE
        PendingOperationType.INDEX_UPDATE -> error("Index-update journal must not be serialized")
    }

    private fun IndexPendingOperationType.toEntityType(): PendingOperationType = when (this) {
        IndexPendingOperationType.CREATE_FOLDER -> PendingOperationType.CREATE_FOLDER
        IndexPendingOperationType.RENAME -> PendingOperationType.RENAME
        IndexPendingOperationType.MOVE -> PendingOperationType.MOVE
        IndexPendingOperationType.DELETE_FOLDER -> PendingOperationType.DELETE_FOLDER
        IndexPendingOperationType.DELETE -> PendingOperationType.DELETE
        IndexPendingOperationType.INDEX_UPDATE -> error("Index-update journal is not a cloud payload")
    }

    private fun PendingOperationStatus.toIndexStatus(): IndexPendingOperationStatus = when (this) {
        PendingOperationStatus.PENDING -> IndexPendingOperationStatus.PENDING
        PendingOperationStatus.RUNNING -> IndexPendingOperationStatus.RUNNING
        PendingOperationStatus.FAILED -> IndexPendingOperationStatus.FAILED
    }

    private fun IndexPendingOperationStatus.toEntityStatus(): PendingOperationStatus = when (this) {
        IndexPendingOperationStatus.PENDING -> PendingOperationStatus.PENDING
        IndexPendingOperationStatus.RUNNING -> PendingOperationStatus.RUNNING
        IndexPendingOperationStatus.FAILED -> PendingOperationStatus.FAILED
    }

    private fun parseStringMap(encoded: String): Map<String, String> =
        json.parseToJsonElement(encoded).jsonObject.mapValues { (_, value) ->
            value.jsonPrimitive.contentOrNull ?: error("Pending-operation value must be a string")
        }

    private fun parseLongList(encoded: String): List<Long> =
        json.parseToJsonElement(encoded).jsonArray.map { it.jsonPrimitive.long }

    private fun stringMapJson(values: Map<String, String>): String =
        JsonObject(values.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()

    private fun longListJson(values: List<Long>): String =
        JsonArray(values.sorted().map(::JsonPrimitive)).toString()

    private fun isCompleteUploadCandidate(
        file: FileEntity,
        chunks: List<ChunkEntity>,
    ): Boolean {
        if (
            file.isCloudIndexed ||
            file.status != FileStatus.UPLOADING ||
            file.uploadedAtEpochMillis != null ||
            file.sha256?.matches(Regex("^[0-9a-f]{64}$")) != true ||
            file.wrappedDataKey?.size != 66 ||
            chunks.size != file.chunkCount
        ) {
            return false
        }
        return chunks.all { chunk ->
            chunk.uploadStatus == ChunkUploadStatus.UPLOADED &&
                chunk.messageId != null &&
                !chunk.telegramFileId.isNullOrBlank() &&
                chunk.nonce?.size == 12
        }
    }

    private val AES_GCM_PARAMETERS = AesGcmParameters(
        algorithm = "AES-256-GCM",
        formatVersion = 1,
        nonceLengthBytes = 12,
        tagLengthBits = 128,
        keyLengthBits = 256,
    )

    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }
}
