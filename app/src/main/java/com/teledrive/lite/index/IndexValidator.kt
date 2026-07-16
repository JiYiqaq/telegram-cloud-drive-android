package com.teledrive.lite.index

import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.crypto.KeyDerivation
import java.util.UUID

enum class IndexFormatFailure {
    MALFORMED,
    UNSUPPORTED_SCHEMA,
    UNSUPPORTED_VERSION,
    NON_CANONICAL,
    INVALID_DATA,
}

class IndexFormatException(
    val failure: IndexFormatFailure,
    cause: Throwable? = null,
) : IllegalArgumentException(failure.name, cause)

object IndexValidator {
    fun requireValid(payload: CloudIndexPayload) {
        if (payload.schema != CloudIndexPayload.SCHEMA) {
            fail(IndexFormatFailure.UNSUPPORTED_SCHEMA)
        }
        if (payload.formatVersion != CloudIndexPayload.CURRENT_FORMAT_VERSION) {
            fail(IndexFormatFailure.UNSUPPORTED_VERSION)
        }
        if (
            !APP_VERSION.matches(payload.appVersion) ||
            payload.revision < 1 ||
            payload.currentIndexMessageId <= 0 ||
            payload.createdAtEpochMillis < 0 ||
            payload.updatedAtEpochMillis < payload.createdAtEpochMillis ||
            payload.rootFolderId != ROOT_ID
        ) {
            fail()
        }
        requireValidPointer(payload)
        requireValidCryptography(payload)
        requireValidGraph(payload)
    }

    internal fun canonicalize(payload: CloudIndexPayload): CloudIndexPayload = payload.copy(
        folders = payload.folders.sortedWith(
            compareBy<IndexFolder> { it.id != ROOT_ID }.thenBy(IndexFolder::id),
        ),
        files = payload.files.sortedBy(IndexFile::id),
        chunks = payload.chunks.sortedWith(
            compareBy(IndexChunk::fileId)
                .thenBy(IndexChunk::partIndex)
                .thenBy(IndexChunk::id),
        ),
        pendingOperations = payload.pendingOperations
            .map { operation ->
                operation.copy(
                    payload = operation.payload?.toSortedMap(),
                    remainingMessageIds = operation.remainingMessageIds?.sorted(),
                )
            }
            .sortedBy(IndexPendingOperation::id),
    )

    private fun requireValidPointer(payload: CloudIndexPayload) {
        val previous = payload.previous
        if (payload.revision == 1L) {
            if (previous != null) fail()
            return
        }
        if (
            previous == null ||
            previous.revision != payload.revision - 1 ||
            previous.messageId <= 0 ||
            previous.messageId == payload.currentIndexMessageId ||
            !isValidOpaqueRemoteId(previous.fileId)
        ) {
            fail()
        }
    }

    private fun requireValidCryptography(payload: CloudIndexPayload) {
        val kdf = payload.keyDerivation
        if (
            kdf.algorithm != KeyDerivation.ALGORITHM ||
            kdf.salt.size !in 1..KeyDerivation.MAX_SALT_BYTES ||
            kdf.iterations !in 1..KeyDerivation.MAX_ITERATIONS ||
            kdf.keyLengthBytes != KeyDerivation.MASTER_KEY_BYTES
        ) {
            fail()
        }
        requireValidAesGcm(payload.encryptionParameters.index)
        requireValidAesGcm(payload.encryptionParameters.file)
    }

    private fun requireValidAesGcm(parameters: AesGcmParameters) {
        if (
            parameters.algorithm != AES_GCM_ALGORITHM ||
            parameters.formatVersion != AES_GCM_FORMAT_VERSION ||
            parameters.nonceLengthBytes != GCM_NONCE_BYTES ||
            parameters.tagLengthBits != GCM_TAG_BITS ||
            parameters.keyLengthBits != AES_KEY_BITS
        ) {
            fail()
        }
    }

    private fun requireValidGraph(payload: CloudIndexPayload) {
        val folders = payload.folders
        val files = payload.files
        val chunks = payload.chunks
        val operations = payload.pendingOperations

        if (
            folders.size > MAX_ENTRIES ||
            files.size > MAX_ENTRIES ||
            chunks.size > MAX_ENTRIES ||
            operations.size > MAX_ENTRIES ||
            folders.map(IndexFolder::id).toSet().size != folders.size ||
            files.map(IndexFile::id).toSet().size != files.size ||
            chunks.map(IndexChunk::id).toSet().size != chunks.size ||
            operations.map(IndexPendingOperation::id).toSet().size != operations.size
        ) {
            fail()
        }

        val foldersById = folders.associateBy(IndexFolder::id)
        val roots = folders.filter { it.parentId == null }
        if (roots.size != 1 || roots.single().id != ROOT_ID) fail()
        folders.forEach { folder ->
            if (
                (folder.id != ROOT_ID && !isCanonicalUuid(folder.id)) ||
                !isValidName(folder.name) ||
                folder.createdAtEpochMillis < 0 ||
                folder.updatedAtEpochMillis < folder.createdAtEpochMillis ||
                (folder.id == ROOT_ID && folder.parentId != null) ||
                (folder.id != ROOT_ID && folder.parentId !in foldersById)
            ) {
                fail()
            }
            requirePathToRoot(folder.id, foldersById)
        }

        val occupiedNames = mutableSetOf<Pair<String, String>>()
        folders.filter { it.parentId != null }.forEach { folder ->
            if (!occupiedNames.add(checkNotNull(folder.parentId) to sqliteNoCaseKey(folder.name))) {
                fail()
            }
        }

        val filesById = files.associateBy(IndexFile::id)
        files.forEach { file ->
            val expectedChunks = expectedChunkCount(file.sizeBytes, file.chunkSizeBytes)
            if (
                !isCanonicalUuid(file.id) ||
                file.parentFolderId !in foldersById ||
                !isValidName(file.name) ||
                !isValidMimeType(file.mimeType) ||
                file.sizeBytes < 0 ||
                file.createdAtEpochMillis < 0 ||
                file.modifiedAtEpochMillis < file.createdAtEpochMillis ||
                file.uploadedAtEpochMillis < 0 ||
                !SHA_256.matches(file.sha256) ||
                file.encryptionFormatVersion != AES_GCM_FORMAT_VERSION ||
                expectedChunks == null ||
                file.chunkCount != expectedChunks ||
                file.wrappedDataKey.size != WRAPPED_DATA_KEY_BYTES ||
                !file.isCloudIndexed
            ) {
                fail()
            }
            if (!occupiedNames.add(file.parentFolderId to sqliteNoCaseKey(file.name))) fail()
        }

        if (
            chunks.map { it.fileId to it.partIndex }.toSet().size != chunks.size ||
            chunks.map(IndexChunk::messageId).toSet().size != chunks.size
        ) {
            fail()
        }
        val chunksByFile = chunks.groupBy(IndexChunk::fileId)
        chunks.forEach { chunk ->
            if (
                !isCanonicalUuid(chunk.id) ||
                chunk.fileId !in filesById ||
                chunk.partIndex < 0 ||
                chunk.messageId <= 0 ||
                chunk.messageId == payload.currentIndexMessageId ||
                chunk.messageId == payload.previous?.messageId ||
                !isValidOpaqueRemoteId(chunk.telegramFileId) ||
                chunk.nonce.size != GCM_NONCE_BYTES ||
                chunk.encryptedSizeBytes < CryptoEngine.ENVELOPE_OVERHEAD_BYTES
            ) {
                fail()
            }
        }
        files.forEach { file ->
            val fileChunks = chunksByFile[file.id].orEmpty()
            if (
                fileChunks.size != file.chunkCount ||
                fileChunks.map(IndexChunk::partIndex).toSet() != (0 until file.chunkCount).toSet()
            ) {
                fail()
            }
            val allowedStatuses = if (file.status == IndexFileStatus.AVAILABLE) {
                setOf(IndexChunkStatus.UPLOADED)
            } else {
                DELETION_CHUNK_STATUSES
            }
            if (fileChunks.any { it.uploadStatus !in allowedStatuses }) fail()
        }

        requireValidOperations(payload, foldersById, filesById, chunksByFile)
    }

    private fun requireValidOperations(
        payload: CloudIndexPayload,
        foldersById: Map<String, IndexFolder>,
        filesById: Map<String, IndexFile>,
        chunksByFile: Map<String, List<IndexChunk>>,
    ) {
        val operations = payload.pendingOperations
        val deletionOperations = operations
            .filter { it.type == IndexPendingOperationType.DELETE }
            .associateBy(IndexPendingOperation::targetId)
        if (
            deletionOperations.size != operations.count { it.type == IndexPendingOperationType.DELETE }
        ) {
            fail()
        }

        operations.forEach { operation ->
            if (
                operation.targetId.isBlank() ||
                operation.baseRevision !in 0..payload.revision ||
                (operation.candidateRevision != null &&
                    operation.candidateRevision !in operation.baseRevision..payload.revision) ||
                (operation.indexConfirmedAtEpochMillis != null &&
                    (operation.indexConfirmedAtEpochMillis < 0 || operation.candidateRevision == null)) ||
                operation.attempt < 0 ||
                (operation.nextRetryAtEpochMillis != null && operation.nextRetryAtEpochMillis < 0) ||
                !isValidErrorCode(operation.errorCode) ||
                operation.createdAtEpochMillis < 0 ||
                operation.updatedAtEpochMillis < operation.createdAtEpochMillis
            ) {
                fail()
            }

            when (operation.type) {
                IndexPendingOperationType.CREATE_FOLDER -> {
                    val target = foldersById[operation.targetId] ?: fail()
                    val values = requirePayload(operation, CREATE_FOLDER_PAYLOAD_KEYS)
                    if (
                        !isCanonicalUuid(operation.id) ||
                        operation.remainingMessageIds != null ||
                        values["parentId"] != target.parentId ||
                        values["name"] != target.name
                    ) {
                        fail()
                    }
                }

                IndexPendingOperationType.RENAME -> {
                    val targetName = foldersById[operation.targetId]?.name
                        ?: filesById[operation.targetId]?.name
                        ?: fail()
                    val values = requirePayload(operation, RENAME_PAYLOAD_KEYS)
                    if (
                        !isCanonicalUuid(operation.id) ||
                        operation.remainingMessageIds != null ||
                        !isValidName(checkNotNull(values["oldName"])) ||
                        values["newName"] != targetName
                    ) {
                        fail()
                    }
                }

                IndexPendingOperationType.MOVE -> {
                    val currentParent = foldersById[operation.targetId]?.parentId
                        ?: filesById[operation.targetId]?.parentFolderId
                        ?: fail()
                    val values = requirePayload(operation, MOVE_PAYLOAD_KEYS)
                    if (
                        !isCanonicalUuid(operation.id) ||
                        operation.remainingMessageIds != null ||
                        values["targetFolderId"] != currentParent ||
                        values["fromParentId"] !in foldersById
                    ) {
                        fail()
                    }
                }

                IndexPendingOperationType.DELETE_FOLDER -> {
                    val values = requirePayload(operation, DELETE_FOLDER_PAYLOAD_KEYS)
                    if (
                        !isCanonicalUuid(operation.id) ||
                        !isCanonicalUuid(operation.targetId) ||
                        operation.targetId in foldersById ||
                        operation.targetId in filesById ||
                        values["parentId"] !in foldersById ||
                        operation.remainingMessageIds != null
                    ) {
                        fail()
                    }
                }

                IndexPendingOperationType.DELETE -> {
                    val file = filesById[operation.targetId] ?: fail()
                    val remaining = operation.remainingMessageIds ?: fail()
                    val expectedRemaining = chunksByFile[file.id].orEmpty()
                        .filter { it.uploadStatus != IndexChunkStatus.DELETED }
                        .map(IndexChunk::messageId)
                        .toSet()
                    if (
                        operation.id != deletionOperationId(file.id) ||
                        operation.payload != null ||
                        remaining.any { it <= 0 } ||
                        remaining.toSet().size != remaining.size ||
                        remaining.toSet() != expectedRemaining ||
                        file.status == IndexFileStatus.AVAILABLE ||
                        (file.status == IndexFileStatus.PARTIALLY_DELETED && remaining.isEmpty())
                    ) {
                        fail()
                    }
                }

                IndexPendingOperationType.INDEX_UPDATE -> {
                    if (
                        !isCanonicalUuid(operation.id) ||
                        operation.targetId != INDEX_TARGET_ID ||
                        operation.remainingMessageIds != null
                    ) {
                        fail()
                    }
                }
            }
        }

        filesById.values.forEach { file ->
            val deletion = deletionOperations[file.id]
            if (
                (file.status == IndexFileStatus.AVAILABLE && deletion != null) ||
                (file.status != IndexFileStatus.AVAILABLE && deletion == null)
            ) {
                fail()
            }
        }
    }

    private fun requirePayload(
        operation: IndexPendingOperation,
        expectedKeys: Set<String>,
    ): Map<String, String> {
        val payload = operation.payload ?: fail()
        if (payload.keys != expectedKeys || payload.values.any { it.length > MAX_PAYLOAD_VALUE_LENGTH }) {
            fail()
        }
        return payload
    }

    private fun requirePathToRoot(
        folderId: String,
        foldersById: Map<String, IndexFolder>,
    ) {
        val visited = mutableSetOf<String>()
        var currentId: String? = folderId
        while (currentId != null) {
            if (!visited.add(currentId)) fail()
            val current = foldersById[currentId] ?: fail()
            currentId = current.parentId
        }
        if (ROOT_ID !in visited) fail()
    }

    private fun expectedChunkCount(sizeBytes: Long, chunkSizeBytes: Int): Int? {
        if (sizeBytes < 0 || chunkSizeBytes <= 0) return null
        val count = if (sizeBytes == 0L) 1L else ((sizeBytes - 1) / chunkSizeBytes) + 1
        return count.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun isValidName(value: String): Boolean =
        value.isNotBlank() &&
            value == value.trim() &&
            value != "." &&
            value != ".." &&
            value.length <= MAX_NAME_LENGTH &&
            '/' !in value &&
            '\\' !in value &&
            value.none(Char::isISOControl)

    private fun isValidMimeType(value: String): Boolean =
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_MIME_TYPE_LENGTH &&
            value.none(Char::isISOControl)

    private fun isValidOpaqueRemoteId(value: String): Boolean =
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_REMOTE_ID_LENGTH &&
            value.none(Char::isISOControl)

    private fun isValidErrorCode(value: String?): Boolean =
        value == null ||
            (value.isNotBlank() &&
                value == value.trim() &&
                value.length <= MAX_ERROR_CODE_LENGTH &&
                value.none(Char::isISOControl))

    private fun sqliteNoCaseKey(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                if (character in 'A'..'Z') {
                    (character.code + ASCII_CASE_OFFSET).toChar()
                } else {
                    character
                },
            )
        }
    }

    private fun deletionOperationId(fileId: String): String = "delete:$fileId"

    private fun fail(failure: IndexFormatFailure = IndexFormatFailure.INVALID_DATA): Nothing =
        throw IndexFormatException(failure)

    private const val ROOT_ID = "root"
    private const val INDEX_TARGET_ID = "cloud-index"
    private const val AES_GCM_ALGORITHM = "AES-256-GCM"
    private const val AES_GCM_FORMAT_VERSION = 1
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_BITS = 256
    private const val WRAPPED_DATA_KEY_BYTES =
        CryptoEngine.ENVELOPE_OVERHEAD_BYTES + CryptoEngine.DATA_KEY_BYTES
    private const val MAX_ENTRIES = 1_000_000
    private const val MAX_NAME_LENGTH = 255
    private const val MAX_MIME_TYPE_LENGTH = 255
    private const val MAX_REMOTE_ID_LENGTH = 1_024
    private const val MAX_ERROR_CODE_LENGTH = 128
    private const val MAX_PAYLOAD_VALUE_LENGTH = 1_024
    private const val ASCII_CASE_OFFSET = 32
    private val APP_VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$")
    private val SHA_256 = Regex("^[0-9a-f]{64}$")
    private val DELETION_CHUNK_STATUSES = setOf(
        IndexChunkStatus.UPLOADED,
        IndexChunkStatus.DELETING,
        IndexChunkStatus.DELETED,
        IndexChunkStatus.FAILED,
    )
    private val CREATE_FOLDER_PAYLOAD_KEYS = setOf("parentId", "name")
    private val RENAME_PAYLOAD_KEYS = setOf("oldName", "newName")
    private val MOVE_PAYLOAD_KEYS = setOf("fromParentId", "targetFolderId")
    private val DELETE_FOLDER_PAYLOAD_KEYS = setOf("parentId")
}
