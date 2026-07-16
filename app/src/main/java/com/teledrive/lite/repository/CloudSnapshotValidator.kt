package com.teledrive.lite.repository

import com.teledrive.lite.database.ChunkEntity
import com.teledrive.lite.database.FileEntity
import com.teledrive.lite.database.FolderEntity
import com.teledrive.lite.database.IndexStateEntity
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.FolderNode
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.PendingOperationType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

object DeletionOperationId {
    fun forFile(fileId: String): String = "delete:$fileId"
}

object FileChunkLayout {
    fun expectedChunkCount(sizeBytes: Long, chunkSizeBytes: Int): Int? {
        if (sizeBytes < 0 || chunkSizeBytes <= 0) return null
        val count = if (sizeBytes == 0L) 1L else ((sizeBytes - 1L) / chunkSizeBytes) + 1L
        return count.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }
}

object CloudSnapshotValidator {
    fun requireValid(snapshot: CloudCacheSnapshot) {
        val folders = snapshot.folders
        val files = snapshot.files
        val chunks = snapshot.chunks
        val indexState = snapshot.indexState
        if (
            indexState.id != IndexStateEntity.SINGLETON_ID ||
            indexState.rootFolderId != FolderTreeValidator.ROOT_ID ||
            indexState.syncStatus != IndexSyncStatus.SYNCED ||
            indexState.revision < 1 ||
            indexState.currentIndexMessageId == null ||
            indexState.currentIndexMessageId <= 0 ||
            indexState.currentIndexFileId.isNullOrBlank() ||
            indexState.lastSyncedAtEpochMillis == null ||
            indexState.lastSyncedAtEpochMillis <= 0 ||
            (indexState.revision == 1L && indexState.previousIndexMessageId != null) ||
            (
                indexState.revision > 1L &&
                    (
                        indexState.previousIndexMessageId == null ||
                            indexState.previousIndexMessageId <= 0 ||
                            indexState.previousIndexMessageId >= indexState.currentIndexMessageId
                        )
                )
        ) {
            fail()
        }
        if (folders.map(FolderEntity::id).toSet().size != folders.size) fail()
        val folderNodes = folders.map { FolderNode(it.id, it.parentId) }
        val roots = folders.filter { it.parentId == null }
        if (roots.size != 1 || roots.single().id != FolderTreeValidator.ROOT_ID) fail()
        try {
            folders.forEach { folder ->
                if (!DriveNameValidator.isValid(folder.name)) fail()
                val path = FolderTreeValidator.pathTo(folder.id, folderNodes)
                if (path.firstOrNull()?.id != FolderTreeValidator.ROOT_ID) fail()
            }
        } catch (_: FolderMoveException) {
            fail()
        }
        val folderIds = folders.mapTo(mutableSetOf(), FolderEntity::id)
        if (
            files.map(FileEntity::id).toSet().size != files.size ||
            files.any {
                    it.parentFolderId !in folderIds ||
                    !DriveNameValidator.isValid(it.name) ||
                    !it.isCloudIndexed ||
                    it.status !in CLOUD_SNAPSHOT_FILE_STATES ||
                    it.uploadedAtEpochMillis == null ||
                    it.sha256?.matches(SHA_256_PATTERN) != true ||
                    it.wrappedDataKey?.isEmpty() != false
            }
        ) {
            fail()
        }
        val occupiedNames = mutableSetOf<Pair<String, String>>()
        folders.filter { it.parentId != null }.forEach { folder ->
            val key = checkNotNull(folder.parentId) to
                DriveNameEquivalence.sqliteNoCaseKey(folder.name)
            if (!occupiedNames.add(key)) fail()
        }
        files.forEach { file ->
            val key = file.parentFolderId to DriveNameEquivalence.sqliteNoCaseKey(file.name)
            if (!occupiedNames.add(key)) fail()
        }
        val fileIds = files.mapTo(mutableSetOf(), FileEntity::id)
        if (
            chunks.map(ChunkEntity::id).toSet().size != chunks.size ||
            chunks.map { it.fileId to it.partIndex }.toSet().size != chunks.size ||
            chunks.mapNotNull(ChunkEntity::messageId).toSet().size != chunks.size ||
            chunks.any { it.fileId !in fileIds }
        ) {
            fail()
        }
        val chunksByFile = chunks.groupBy(ChunkEntity::fileId)
        files.forEach { file ->
            val fileChunks = chunksByFile[file.id].orEmpty()
            if (!hasCompleteChunkSet(file, fileChunks)) fail()
            val allowedChunkStatuses = if (file.status == FileStatus.AVAILABLE) {
                setOf(ChunkUploadStatus.UPLOADED)
            } else {
                DELETION_CHUNK_STATES
            }
            if (
                fileChunks.any { chunk ->
                    chunk.uploadStatus !in allowedChunkStatuses ||
                        chunk.messageId == null || chunk.messageId <= 0 ||
                        chunk.telegramFileId.isNullOrBlank() ||
                        chunk.nonce?.size != GCM_NONCE_SIZE_BYTES ||
                        chunk.encryptedSizeBytes <= 0
                }
            ) {
                fail()
            }
        }
        if (snapshot.pendingOperations.map { it.id }.toSet().size != snapshot.pendingOperations.size) {
            fail()
        }
        val deleteOperationsByTarget = snapshot.pendingOperations
            .filter { it.type == PendingOperationType.DELETE }
            .associateBy { it.targetId }
        if (
            deleteOperationsByTarget.size !=
            snapshot.pendingOperations.count { it.type == PendingOperationType.DELETE } ||
            files.any {
                it.status == FileStatus.AVAILABLE && it.id in deleteOperationsByTarget
            }
        ) {
            fail()
        }
        files.filter { it.status in DELETION_FILE_STATES }.forEach { file ->
            val operation = deleteOperationsByTarget[file.id] ?: fail()
            val remainingIds = parseMessageIds(operation.remainingMessageIdsJson)
            val expectedRemainingIds = chunksByFile[file.id].orEmpty()
                .filter { it.uploadStatus != ChunkUploadStatus.DELETED }
                .mapNotNullTo(mutableSetOf(), ChunkEntity::messageId)
            if (
                operation.id != DeletionOperationId.forFile(file.id) ||
                operation.baseRevision == null ||
                operation.baseRevision > indexState.revision ||
                remainingIds != expectedRemainingIds ||
                (file.status == FileStatus.PARTIALLY_DELETED && remainingIds.isEmpty())
            ) {
                fail()
            }
        }
        snapshot.pendingOperations.forEach { operation ->
            val baseRevision = operation.baseRevision ?: fail()
            if (operation.targetId.isBlank() || baseRevision > indexState.revision) fail()
            if (
                operation.candidateRevision != null &&
                operation.candidateRevision < baseRevision
            ) {
                fail()
            }
            when (operation.type) {
                PendingOperationType.CREATE_FOLDER -> {
                    if (operation.targetId !in folderIds || operation.payloadJson == null) fail()
                }

                PendingOperationType.RENAME,
                PendingOperationType.MOVE -> {
                    if (
                        (operation.targetId !in folderIds && operation.targetId !in fileIds) ||
                        operation.payloadJson == null
                    ) {
                        fail()
                    }
                }

                PendingOperationType.DELETE_FOLDER -> {
                    val payload = try {
                        Json.parseToJsonElement(checkNotNull(operation.payloadJson)).jsonObject
                    } catch (_: Exception) {
                        fail()
                    }
                    if (
                        operation.targetId in folderIds ||
                        operation.targetId in fileIds ||
                        payload.keys != setOf("parentId") ||
                        payload.getValue("parentId").jsonPrimitive.content !in folderIds ||
                        operation.remainingMessageIdsJson != null
                    ) {
                        fail()
                    }
                }

                PendingOperationType.DELETE -> Unit
                PendingOperationType.INDEX_UPDATE -> {
                    if (operation.targetId != IndexStateEntity.SINGLETON_ID) fail()
                }
            }
        }
    }

    fun hasCompleteChunkSet(file: FileEntity, chunks: List<ChunkEntity>): Boolean =
        file.chunkCount == FileChunkLayout.expectedChunkCount(file.sizeBytes, file.chunkSizeBytes) &&
            chunks.size == file.chunkCount &&
            chunks.map(ChunkEntity::partIndex).toSet() == (0 until file.chunkCount).toSet()

    private fun parseMessageIds(value: String?): Set<Long> = try {
        checkNotNull(value)
        val ids = Json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.long }
        if (ids.any { it <= 0 } || ids.toSet().size != ids.size) fail()
        ids.toSet()
    } catch (_: Exception) {
        fail()
    }

    private fun fail(): Nothing =
        throw DriveRepositoryException(DriveRepositoryFailure.INVALID_CLOUD_SNAPSHOT)

    private const val GCM_NONCE_SIZE_BYTES = 12
    private val CLOUD_SNAPSHOT_FILE_STATES = setOf(
        FileStatus.AVAILABLE,
        FileStatus.DELETING,
        FileStatus.PARTIALLY_DELETED,
    )
    private val DELETION_FILE_STATES = setOf(
        FileStatus.DELETING,
        FileStatus.PARTIALLY_DELETED,
    )
    private val DELETION_CHUNK_STATES = setOf(
        ChunkUploadStatus.UPLOADED,
        ChunkUploadStatus.DELETING,
        ChunkUploadStatus.DELETED,
        ChunkUploadStatus.FAILED,
    )
    private val SHA_256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
}
