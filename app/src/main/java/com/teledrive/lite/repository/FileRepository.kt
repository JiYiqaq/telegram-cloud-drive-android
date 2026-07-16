package com.teledrive.lite.repository

import androidx.room.withTransaction
import com.teledrive.lite.database.ChunkEntity
import com.teledrive.lite.database.FileEntity
import com.teledrive.lite.database.FolderEntity
import com.teledrive.lite.database.IndexStateEntity
import com.teledrive.lite.database.PendingOperationEntity
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.DirectoryEntry
import com.teledrive.lite.model.DirectorySnapshot
import com.teledrive.lite.model.EntryKind
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.FolderNode
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.PendingOperationStatus
import com.teledrive.lite.model.PendingOperationType
import com.teledrive.lite.model.SortDirection
import com.teledrive.lite.model.SortMode
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

enum class DriveRepositoryFailure(val chineseMessage: String) {
    PARENT_NOT_FOUND("目标文件夹不存在。"),
    ENTRY_NOT_FOUND("文件或文件夹不存在。"),
    NAME_CONFLICT("目标文件夹中已存在同名项目。"),
    INVALID_FOLDER_MOVE("无法移动文件夹：目标会形成无效目录结构。"),
    ROOT_IS_IMMUTABLE("根目录不能移动、改名或删除。"),
    NON_EMPTY_CONFIRMATION_REQUIRED("删除非空文件夹前需要明确确认。"),
    FOLDER_NOT_EMPTY("文件夹仍包含项目，不能直接物理删除。"),
    INVALID_FILE_STATE("文件当前状态不允许执行该操作。"),
    REMOTE_DELETE_INCOMPLETE("仍有远端分块未确认删除，已保留本地恢复元数据。"),
    INDEX_CONFIRMATION_REQUIRED("尚未确认新的云端索引，不能清理本地元数据。"),
    INVALID_CLOUD_SNAPSHOT("云端索引快照无效，旧的本地缓存保持不变。"),
    DELETE_OPERATION_REQUIRED("缺少与该文件绑定的删除操作记录。"),
    ACTIVE_TRANSFER_EXISTS("仍有活动传输任务，不能替换或清理缓存。"),
    LOCAL_RECOVERY_DATA_EXISTS("存在尚未同步或待清理的本地数据，已拒绝覆盖缓存。"),
    STALE_CLOUD_SNAPSHOT("云端索引版本早于本地稳定版本，已拒绝回退。"),
    CLOUD_INDEX_CONFLICT("检测到同版本分叉或前序索引不匹配，已停止同步。"),
}

class DriveRepositoryException(
    val failure: DriveRepositoryFailure,
) : IllegalStateException(failure.chineseMessage)

data class FolderDeletionPlan(
    val folderIdsInDeletionOrder: List<String>,
    val fileIds: List<String>,
)

data class BatchItemResult(
    val entryId: String,
    val succeeded: Boolean,
    val failure: DriveRepositoryFailure? = null,
) {
    init {
        require(succeeded == (failure == null))
    }
}

data class CloudCacheSnapshot(
    val folders: List<FolderEntity>,
    val files: List<FileEntity>,
    val chunks: List<ChunkEntity>,
    val indexState: IndexStateEntity,
    val pendingOperations: List<PendingOperationEntity> = emptyList(),
)

data class FileDeletionStart(
    val operationId: String,
    val canceledWorkRequestIds: List<String>,
)

class FileRepository(
    private val database: TeleDriveDatabase,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val operationIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val folderDao = database.folderDao()
    private val fileDao = database.fileDao()
    private val chunkDao = database.chunkDao()
    private val indexStateDao = database.indexStateDao()
    private val transferTaskDao = database.transferTaskDao()
    private val pendingOperationDao = database.pendingOperationDao()
    private val mutationMutex = Mutex()

    suspend fun initializeRoot() = mutationMutex.withLock {
        if (folderDao.getById(FolderTreeValidator.ROOT_ID) == null) {
            val now = clock()
            folderDao.upsert(
                FolderEntity(
                    id = FolderTreeValidator.ROOT_ID,
                    name = ROOT_NAME,
                    parentId = null,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
        }
    }

    fun observeDirectory(
        folderId: String,
        sortMode: SortMode,
        sortDirection: SortDirection,
    ): Flow<DirectorySnapshot> = combine(
        folderDao.observeAll(),
        fileDao.observeInFolder(folderId),
    ) { folders, files ->
        val folder = folders.firstOrNull { it.id == folderId }
            ?: throw DriveRepositoryException(DriveRepositoryFailure.ENTRY_NOT_FOUND)
        val folderNodes = folders.map { FolderNode(it.id, it.parentId) }
        val names = folders.associate { it.id to it.name }
        val breadcrumbs = FolderTreeValidator.pathTo(folder.id, folderNodes).map { node ->
            node.id to checkNotNull(names[node.id])
        }
        val childFolders = folders.asSequence()
            .filter { it.parentId == folderId }
            .map { it.toDirectoryEntry() }
        val childFiles = files.asSequence().map { it.toDirectoryEntry() }
        DirectorySnapshot(
            folderId = folderId,
            breadcrumbs = breadcrumbs,
            entries = DirectoryEntrySorter.sort(
                (childFolders + childFiles).toList(),
                sortMode,
                sortDirection,
            ),
        )
    }

    fun search(query: String): Flow<List<DirectoryEntry>> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return flowOf(emptyList())
        return combine(folderDao.search(normalized), fileDao.search(normalized)) { folders, files ->
            DirectoryEntrySorter.sort(
                folders.map { it.toDirectoryEntry() } + files.map { it.toDirectoryEntry() },
                SortMode.NAME,
                SortDirection.ASCENDING,
            )
        }
    }

    suspend fun createFolder(parentId: String, name: String): FolderEntity = mutationMutex.withLock {
        DriveNameValidator.requireValid(name)
        database.withTransaction {
            requireFolder(parentId)
            requireNameAvailable(parentId, name)
            val now = clock()
            val folder = FolderEntity(idGenerator(), name, parentId, now, now)
            folderDao.upsert(folder)
            recordPendingMutation(
                PendingOperationType.CREATE_FOLDER,
                folder.id,
                mutationPayload("parentId" to parentId, "name" to name),
                now,
            )
            folder
        }
    }

    suspend fun renameFolder(folderId: String, name: String) = mutationMutex.withLock {
        if (folderId == FolderTreeValidator.ROOT_ID) fail(DriveRepositoryFailure.ROOT_IS_IMMUTABLE)
        DriveNameValidator.requireValid(name)
        database.withTransaction {
            val folder = folderDao.getById(folderId) ?: fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            if (folder.name == name) return@withTransaction
            requireNameAvailable(
                parentId = checkNotNull(folder.parentId),
                name = name,
                excludedFolderId = folder.id,
            )
            val now = clock()
            if (folderDao.rename(folderId, name, now) != 1) {
                fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            }
            recordPendingMutation(
                PendingOperationType.RENAME,
                folderId,
                mutationPayload("oldName" to folder.name, "newName" to name),
                now,
            )
        }
    }

    suspend fun renameFile(fileId: String, name: String) = mutationMutex.withLock {
        DriveNameValidator.requireValid(name)
        database.withTransaction {
            val file = fileDao.getById(fileId) ?: fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            if (file.name == name) return@withTransaction
            requireNameAvailable(
                parentId = file.parentFolderId,
                name = name,
                excludedFileIds = listOf(file.id),
            )
            val now = clock()
            if (fileDao.rename(fileId, name, now) != 1) {
                fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            }
            recordPendingMutation(
                PendingOperationType.RENAME,
                fileId,
                mutationPayload("oldName" to file.name, "newName" to name),
                now,
            )
        }
    }

    suspend fun moveFolder(folderId: String, targetFolderId: String) = mutationMutex.withLock {
        database.withTransaction {
            val folders = folderDao.getAll()
            try {
                FolderTreeValidator.validateMove(
                    folderId,
                    targetFolderId,
                    folders.map { FolderNode(it.id, it.parentId) },
                )
            } catch (_: FolderMoveException) {
                fail(DriveRepositoryFailure.INVALID_FOLDER_MOVE)
            }
            val source = folders.first { it.id == folderId }
            if (source.parentId == targetFolderId) return@withTransaction
            requireNameAvailable(targetFolderId, source.name)
            val now = clock()
            if (folderDao.move(folderId, targetFolderId, now) != 1) {
                fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            }
            recordPendingMutation(
                PendingOperationType.MOVE,
                folderId,
                mutationPayload(
                    "fromParentId" to checkNotNull(source.parentId),
                    "targetFolderId" to targetFolderId,
                ),
                now,
            )
        }
    }

    suspend fun moveFiles(
        fileIds: List<String>,
        targetFolderId: String,
    ): List<BatchItemResult> = mutationMutex.withLock {
        fileIds.distinct().map { fileId ->
            try {
                database.withTransaction {
                    requireFolder(targetFolderId)
                    val file = fileDao.getById(fileId)
                        ?: fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
                    if (file.parentFolderId != targetFolderId) {
                        requireNameAvailable(
                            parentId = targetFolderId,
                            name = file.name,
                            excludedFileIds = listOf(file.id),
                        )
                        val now = clock()
                        if (fileDao.moveAll(listOf(file.id), targetFolderId, now) != 1) {
                            fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
                        }
                        recordPendingMutation(
                            PendingOperationType.MOVE,
                            file.id,
                            mutationPayload(
                                "fromParentId" to file.parentFolderId,
                                "targetFolderId" to targetFolderId,
                            ),
                            now,
                        )
                    }
                }
                BatchItemResult(fileId, succeeded = true)
            } catch (error: DriveRepositoryException) {
                BatchItemResult(fileId, succeeded = false, failure = error.failure)
            }
        }
    }

    suspend fun planFolderDeletion(
        folderId: String,
        confirmed: Boolean,
    ): FolderDeletionPlan = mutationMutex.withLock {
        if (folderId == FolderTreeValidator.ROOT_ID) fail(DriveRepositoryFailure.ROOT_IS_IMMUTABLE)
        database.withTransaction {
            val folders = folderDao.getAll()
            if (folders.none { it.id == folderId }) fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            val allFiles = fileDao.getAll()
            val childrenByParent = folders.groupBy(FolderEntity::parentId)
            val deletionOrder = mutableListOf<String>()
            val visited = mutableSetOf<String>()
            fun visit(id: String) {
                if (!visited.add(id)) fail(DriveRepositoryFailure.INVALID_FOLDER_MOVE)
                childrenByParent[id].orEmpty().forEach { visit(it.id) }
                deletionOrder += id
            }
            visit(folderId)
            val folderIds = deletionOrder.toSet()
            val fileIds = allFiles.filter { it.parentFolderId in folderIds }.map(FileEntity::id)
            val nonEmpty = deletionOrder.size > 1 || fileIds.isNotEmpty()
            if (nonEmpty && !confirmed) {
                fail(DriveRepositoryFailure.NON_EMPTY_CONFIRMATION_REQUIRED)
            }
            FolderDeletionPlan(deletionOrder, fileIds)
        }
    }

    suspend fun deleteEmptyFolder(folderId: String) = mutationMutex.withLock {
        if (folderId == FolderTreeValidator.ROOT_ID) fail(DriveRepositoryFailure.ROOT_IS_IMMUTABLE)
        database.withTransaction {
            val folder = requireFolder(folderId)
            if (folderDao.countChildren(folderId) > 0 || fileDao.countInFolder(folderId) > 0) {
                fail(DriveRepositoryFailure.FOLDER_NOT_EMPTY)
            }
            val now = clock()
            if (folderDao.deleteById(folderId) != 1) fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            recordPendingMutation(
                PendingOperationType.DELETE_FOLDER,
                folderId,
                mutationPayload("parentId" to checkNotNull(folder.parentId)),
                now,
            )
        }
    }

    suspend fun transitionFileStatus(fileId: String, next: FileStatus) = mutationMutex.withLock {
        database.withTransaction {
            val file = fileDao.getById(fileId) ?: fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            if (!FileStateMachine.canTransition(file.status, next, file.isCloudIndexed)) {
                fail(DriveRepositoryFailure.INVALID_FILE_STATE)
            }
            if (fileDao.updateStatus(fileId, next, clock()) != 1) {
                fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            }
        }
    }

    suspend fun transitionFileStatuses(
        fileIds: List<String>,
        next: FileStatus,
    ): List<BatchItemResult> = mutationMutex.withLock {
        fileIds.distinct().map { fileId ->
            try {
                database.withTransaction {
                    val file = fileDao.getById(fileId)
                        ?: fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
                    if (!FileStateMachine.canTransition(file.status, next, file.isCloudIndexed)) {
                        fail(DriveRepositoryFailure.INVALID_FILE_STATE)
                    }
                    if (fileDao.updateStatus(file.id, next, clock()) != 1) {
                        fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
                    }
                }
                BatchItemResult(fileId, succeeded = true)
            } catch (error: DriveRepositoryException) {
                BatchItemResult(fileId, succeeded = false, failure = error.failure)
            }
        }
    }

    suspend fun beginFileDeletion(fileId: String): FileDeletionStart = mutationMutex.withLock {
        database.withTransaction {
            val file = fileDao.getById(fileId) ?: fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            val operationId = deletionOperationId(fileId)
            val existingOperation = pendingOperationDao.getById(operationId)
            val activeTasks = transferTaskDao.getActiveForFile(fileId)
            val now = clock()
            if (activeTasks.isNotEmpty()) {
                if (
                    transferTaskDao.cancelActiveForFile(fileId, FILE_DELETION_REASON, now) !=
                    activeTasks.size
                ) {
                    fail(DriveRepositoryFailure.ACTIVE_TRANSFER_EXISTS)
                }
            }
            if (file.status in FINAL_DELETION_STATES) {
                val operation = existingOperation
                    ?: fail(DriveRepositoryFailure.DELETE_OPERATION_REQUIRED)
                if (
                    operation.type != PendingOperationType.DELETE ||
                    operation.targetId != fileId
                ) {
                    fail(DriveRepositoryFailure.DELETE_OPERATION_REQUIRED)
                }
                return@withTransaction FileDeletionStart(
                    operationId,
                    activeTasks.mapNotNull { it.workRequestId },
                )
            }
            if (!file.isCloudIndexed || !FileStateMachine.canBeginCloudDeletion(file.status)) {
                fail(DriveRepositoryFailure.INVALID_FILE_STATE)
            }
            val chunks = chunkDao.getForFile(fileId)
            if (
                !CloudSnapshotValidator.hasCompleteChunkSet(file, chunks) ||
                chunks.mapNotNull(ChunkEntity::messageId).toSet().size != chunks.size
            ) {
                fail(DriveRepositoryFailure.REMOTE_DELETE_INCOMPLETE)
            }
            val indexState = indexStateDao.get(IndexStateEntity.SINGLETON_ID)
            if (
                indexState == null ||
                indexState.syncStatus != IndexSyncStatus.SYNCED ||
                indexState.currentIndexMessageId == null ||
                indexState.currentIndexFileId.isNullOrBlank() ||
                indexState.lastSyncedAtEpochMillis == null
            ) {
                fail(DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED)
            }
            val remainingMessageIds = messageIdsJson(chunks.mapNotNull { it.messageId }.toSet())
            pendingOperationDao.upsert(
                PendingOperationEntity(
                    id = operationId,
                    type = PendingOperationType.DELETE,
                    targetId = fileId,
                    payloadJson = null,
                    remainingMessageIdsJson = remainingMessageIds,
                    baseRevision = indexState.revision,
                    candidateRevision = null,
                    indexConfirmedAtEpochMillis = null,
                    status = PendingOperationStatus.PENDING,
                    attempt = 0,
                    nextRetryAtEpochMillis = null,
                    errorCode = null,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
            if (fileDao.updateStatus(fileId, FileStatus.DELETING, now) != 1) {
                fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            }
            indexStateDao.upsert(indexState.copy(syncStatus = IndexSyncStatus.DIRTY))
            FileDeletionStart(operationId, activeTasks.mapNotNull { it.workRequestId })
        }
    }

    suspend fun recordChunkDeletionResult(
        fileId: String,
        chunkId: String,
        deleted: Boolean,
        errorCode: String? = null,
    ) = mutationMutex.withLock {
        database.withTransaction {
            val file = fileDao.getById(fileId) ?: fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            if (file.status !in FINAL_DELETION_STATES) {
                fail(DriveRepositoryFailure.INVALID_FILE_STATE)
            }
            if (!deleted && errorCode.isNullOrBlank()) {
                fail(DriveRepositoryFailure.INVALID_FILE_STATE)
            }
            val chunk = chunkDao.getById(chunkId)
                ?.takeIf { it.fileId == fileId }
                ?: fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            val messageId = chunk.messageId
                ?: fail(DriveRepositoryFailure.REMOTE_DELETE_INCOMPLETE)
            val operationId = deletionOperationId(fileId)
            val operation = pendingOperationDao.getById(operationId)
                ?: fail(DriveRepositoryFailure.DELETE_OPERATION_REQUIRED)
            if (
                operation.type != PendingOperationType.DELETE ||
                operation.targetId != fileId
            ) {
                fail(DriveRepositoryFailure.DELETE_OPERATION_REQUIRED)
            }
            val remainingIds = parseRemainingMessageIds(operation.remainingMessageIdsJson)
                .toMutableSet()
            val now = clock()
            val chunkStatus: ChunkUploadStatus
            val nextFileStatus: FileStatus
            val nextOperationStatus: PendingOperationStatus
            val nextError: String?
            val nextAttempt: Int
            if (deleted) {
                remainingIds.remove(messageId)
                chunkStatus = ChunkUploadStatus.DELETED
                nextFileStatus = if (remainingIds.isEmpty()) {
                    FileStatus.DELETING
                } else {
                    file.status
                }
                nextOperationStatus = PendingOperationStatus.PENDING
                nextError = null
                nextAttempt = operation.attempt
            } else {
                remainingIds.add(messageId)
                chunkStatus = ChunkUploadStatus.FAILED
                nextFileStatus = FileStatus.PARTIALLY_DELETED
                nextOperationStatus = PendingOperationStatus.FAILED
                nextError = errorCode
                nextAttempt = operation.attempt + 1
            }
            if (chunkDao.updateStatus(chunkId, chunkStatus) != 1) {
                fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            }
            if (fileDao.updateStatus(fileId, nextFileStatus, now) != 1) {
                fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
            }
            pendingOperationDao.upsert(
                operation.copy(
                    remainingMessageIdsJson = messageIdsJson(remainingIds),
                    status = nextOperationStatus,
                    attempt = nextAttempt,
                    errorCode = nextError,
                    updatedAtEpochMillis = now,
                ),
            )
            val indexState = indexStateDao.get(IndexStateEntity.SINGLETON_ID)
                ?: fail(DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED)
            indexStateDao.upsert(indexState.copy(syncStatus = IndexSyncStatus.DIRTY))
        }
    }

    suspend fun finalizeFileDeletion(
        fileId: String,
        confirmedSnapshot: CloudCacheSnapshot,
    ) {
        CloudSnapshotValidator.requireValid(confirmedSnapshot)
        if (confirmedSnapshot.files.any { it.id == fileId }) {
            fail(DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED)
        }
        mutationMutex.withLock {
            database.withTransaction {
                val file = fileDao.getById(fileId) ?: fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
                if (file.status !in FINAL_DELETION_STATES) {
                    fail(DriveRepositoryFailure.INVALID_FILE_STATE)
                }
                val chunks = chunkDao.getForFile(fileId)
                if (
                    !CloudSnapshotValidator.hasCompleteChunkSet(file, chunks) ||
                    chunks.any { it.uploadStatus != ChunkUploadStatus.DELETED }
                ) {
                    fail(DriveRepositoryFailure.REMOTE_DELETE_INCOMPLETE)
                }
                if (transferTaskDao.countActiveForFile(fileId) != 0) {
                    fail(DriveRepositoryFailure.ACTIVE_TRANSFER_EXISTS)
                }
                val operationId = deletionOperationId(fileId)
                val operation = pendingOperationDao.getById(operationId)
                    ?: fail(DriveRepositoryFailure.DELETE_OPERATION_REQUIRED)
                if (parseRemainingMessageIds(operation.remainingMessageIdsJson).isNotEmpty()) {
                    fail(DriveRepositoryFailure.REMOTE_DELETE_INCOMPLETE)
                }
                val baseRevision = operation.baseRevision
                    ?: fail(DriveRepositoryFailure.DELETE_OPERATION_REQUIRED)
                val currentIndex = indexStateDao.get(IndexStateEntity.SINGLETON_ID)
                    ?: fail(DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED)
                if (
                    operation.type != PendingOperationType.DELETE ||
                    operation.targetId != fileId ||
                    baseRevision > currentIndex.revision ||
                    confirmedSnapshot.indexState.revision - currentIndex.revision != 1L ||
                    confirmedSnapshot.indexState.previousIndexMessageId !=
                    currentIndex.currentIndexMessageId ||
                    confirmedSnapshot.indexState.currentIndexMessageId ==
                    currentIndex.currentIndexMessageId ||
                    confirmedSnapshot.indexState.currentIndexFileId == currentIndex.currentIndexFileId
                ) {
                    fail(DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED)
                }
                verifyDeletionOnlySnapshot(fileId, confirmedSnapshot)
                val now = clock()
                pendingOperationDao.upsert(
                    operation.copy(
                        candidateRevision = confirmedSnapshot.indexState.revision,
                        indexConfirmedAtEpochMillis = now,
                        status = PendingOperationStatus.RUNNING,
                        updatedAtEpochMillis = now,
                    ),
                )
                indexStateDao.deleteAll()
                indexStateDao.upsert(confirmedSnapshot.indexState)
                if (fileDao.deleteById(fileId) != 1) {
                    fail(DriveRepositoryFailure.ENTRY_NOT_FOUND)
                }
                pendingOperationDao.deleteById(operationId)
            }
        }
    }

    suspend fun replaceCloudCache(
        folders: List<FolderEntity>,
        files: List<FileEntity>,
        chunks: List<ChunkEntity>,
        indexState: IndexStateEntity,
    ) = replaceCloudCache(CloudCacheSnapshot(folders, files, chunks, indexState))

    suspend fun replaceCloudCache(snapshot: CloudCacheSnapshot) = mutationMutex.withLock {
        CloudSnapshotValidator.requireValid(snapshot)
        database.withTransaction {
            if (transferTaskDao.countActive() != 0) {
                fail(DriveRepositoryFailure.ACTIVE_TRANSFER_EXISTS)
            }
            val currentFiles = fileDao.getAll()
            val currentPendingOperations = pendingOperationDao.getAll()
            val currentIndex = indexStateDao.get(IndexStateEntity.SINGLETON_ID)
            if (CloudCacheReplacementPolicy.mustPreserveLocalState(
                    hasLocalOnlyFiles = currentFiles.any { !it.isCloudIndexed },
                    currentIndexSyncStatus = currentIndex?.syncStatus,
                    hasUncoveredPendingOperations =
                        !snapshot.pendingOperations.containsAll(currentPendingOperations),
                )
            ) {
                fail(DriveRepositoryFailure.LOCAL_RECOVERY_DATA_EXISTS)
            }
            val replacementDecision = CloudCacheReplacementPolicy.decide(
                current = currentIndex
                    ?.takeIf { it.syncStatus == IndexSyncStatus.SYNCED }
                    ?.let {
                        CloudIndexIdentity(
                            revision = it.revision,
                            currentMessageId = it.currentIndexMessageId,
                            currentFileId = it.currentIndexFileId,
                            previousMessageId = it.previousIndexMessageId,
                        )
                    },
                incoming = CloudIndexIdentity(
                    revision = snapshot.indexState.revision,
                    currentMessageId = snapshot.indexState.currentIndexMessageId,
                    currentFileId = snapshot.indexState.currentIndexFileId,
                    previousMessageId = snapshot.indexState.previousIndexMessageId,
                ),
            )
            when (replacementDecision) {
                CloudCacheReplacementDecision.REPLACE -> Unit
                CloudCacheReplacementDecision.KEEP_CURRENT -> return@withTransaction
                CloudCacheReplacementDecision.REJECT_STALE -> {
                    fail(DriveRepositoryFailure.STALE_CLOUD_SNAPSHOT)
                }

                CloudCacheReplacementDecision.REJECT_FORK -> {
                    fail(DriveRepositoryFailure.CLOUD_INDEX_CONFLICT)
                }
            }
            pendingOperationDao.deleteAll()
            chunkDao.deleteAll()
            fileDao.deleteAll()
            folderDao.deleteAll()
            folderDao.upsertAll(snapshot.folders)
            fileDao.upsertAll(snapshot.files)
            chunkDao.upsertAll(snapshot.chunks)
            indexStateDao.deleteAll()
            indexStateDao.upsert(snapshot.indexState)
            pendingOperationDao.upsertAll(snapshot.pendingOperations)
        }
    }

    private suspend fun requireFolder(id: String): FolderEntity =
        folderDao.getById(id) ?: fail(DriveRepositoryFailure.PARENT_NOT_FOUND)

    private suspend fun recordPendingMutation(
        type: PendingOperationType,
        targetId: String,
        payloadJson: String,
        now: Long,
    ) {
        val currentIndex = indexStateDao.get(IndexStateEntity.SINGLETON_ID)
            ?: IndexStateEntity(
                id = IndexStateEntity.SINGLETON_ID,
                formatVersion = INDEX_FORMAT_VERSION,
                revision = 0,
                rootFolderId = FolderTreeValidator.ROOT_ID,
                currentIndexMessageId = null,
                previousIndexMessageId = null,
                currentIndexFileId = null,
                lastSyncedAtEpochMillis = null,
                syncStatus = IndexSyncStatus.NOT_INITIALIZED,
            )
        pendingOperationDao.upsert(
            PendingOperationEntity(
                id = operationIdGenerator(),
                type = type,
                targetId = targetId,
                payloadJson = payloadJson,
                remainingMessageIdsJson = null,
                baseRevision = currentIndex.revision,
                candidateRevision = null,
                indexConfirmedAtEpochMillis = null,
                status = PendingOperationStatus.PENDING,
                attempt = 0,
                nextRetryAtEpochMillis = null,
                errorCode = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        indexStateDao.upsert(currentIndex.copy(syncStatus = IndexSyncStatus.DIRTY))
    }

    private fun mutationPayload(vararg values: Pair<String, String>): String =
        JsonObject(values.associate { (key, value) -> key to JsonPrimitive(value) }).toString()

    private fun parseRemainingMessageIds(value: String?): Set<Long> = try {
        checkNotNull(value)
        val ids = Json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.long }
        check(ids.all { it > 0 } && ids.toSet().size == ids.size)
        ids.toSet()
    } catch (_: Exception) {
        fail(DriveRepositoryFailure.DELETE_OPERATION_REQUIRED)
    }

    private fun messageIdsJson(messageIds: Set<Long>): String =
        JsonArray(messageIds.sorted().map(::JsonPrimitive)).toString()

    private suspend fun requireNameAvailable(
        parentId: String,
        name: String,
        excludedFolderId: String? = null,
        excludedFileIds: List<String> = emptyList(),
    ) {
        val folderConflict = if (excludedFolderId == null) {
            folderDao.countName(parentId, name) > 0
        } else {
            folderDao.countNameExcluding(parentId, name, excludedFolderId) > 0
        }
        val fileConflict = if (excludedFileIds.isEmpty()) {
            fileDao.countName(parentId, name) > 0
        } else {
            fileDao.countNameExcluding(parentId, name, excludedFileIds) > 0
        }
        if (folderConflict || fileConflict) fail(DriveRepositoryFailure.NAME_CONFLICT)
    }

    private suspend fun verifyDeletionOnlySnapshot(
        deletedFileId: String,
        snapshot: CloudCacheSnapshot,
    ) {
        val localFolders = folderDao.getAll().sortedBy(FolderEntity::id)
        val snapshotFolders = snapshot.folders.sortedBy(FolderEntity::id)
        val localCloudFiles = fileDao.getAll()
            .filter { it.id != deletedFileId && it.isCloudIndexed }
        val snapshotFilesById = snapshot.files.associateBy(FileEntity::id)
        val localPendingOperations = pendingOperationDao.getAll()
            .filterNot { it.id == deletionOperationId(deletedFileId) }
            .sortedBy(PendingOperationEntity::id)
        val snapshotPendingOperations = snapshot.pendingOperations.sortedBy(PendingOperationEntity::id)
        if (
            localFolders != snapshotFolders ||
            localCloudFiles.mapTo(mutableSetOf(), FileEntity::id) != snapshotFilesById.keys ||
            localPendingOperations != snapshotPendingOperations
        ) {
            fail(DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED)
        }
        localCloudFiles.forEach { localFile ->
            val snapshotFile = snapshotFilesById.getValue(localFile.id)
            if (!localFile.matchesCloudSnapshot(snapshotFile)) {
                fail(DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED)
            }
            val localChunks = chunkDao.getForFile(localFile.id).sortedBy(ChunkEntity::partIndex)
            val snapshotChunks = snapshot.chunks
                .filter { it.fileId == localFile.id }
                .sortedBy(ChunkEntity::partIndex)
            if (
                localChunks.size != snapshotChunks.size ||
                localChunks.zip(snapshotChunks).any { (local, remote) ->
                    !local.matchesCloudSnapshot(remote)
                }
            ) {
                fail(DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED)
            }
        }
    }

    private fun FileEntity.matchesCloudSnapshot(other: FileEntity): Boolean =
        id == other.id &&
            name == other.name &&
            mimeType == other.mimeType &&
            sizeBytes == other.sizeBytes &&
            createdAtEpochMillis == other.createdAtEpochMillis &&
            modifiedAtEpochMillis == other.modifiedAtEpochMillis &&
            uploadedAtEpochMillis == other.uploadedAtEpochMillis &&
            parentFolderId == other.parentFolderId &&
            sha256 == other.sha256 &&
            encryptionFormatVersion == other.encryptionFormatVersion &&
            chunkSizeBytes == other.chunkSizeBytes &&
            chunkCount == other.chunkCount &&
            wrappedDataKey.contentEqualsNullable(other.wrappedDataKey) &&
            isCloudIndexed && other.isCloudIndexed &&
            other.status == if (status in FINAL_DELETION_STATES) status else FileStatus.AVAILABLE

    private fun ChunkEntity.matchesCloudSnapshot(other: ChunkEntity): Boolean =
        id == other.id &&
            fileId == other.fileId &&
            partIndex == other.partIndex &&
            messageId == other.messageId &&
            telegramFileId == other.telegramFileId &&
            nonce.contentEqualsNullable(other.nonce) &&
            encryptedSizeBytes == other.encryptedSizeBytes &&
            uploadStatus == other.uploadStatus

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }

    private fun deletionOperationId(fileId: String): String = DeletionOperationId.forFile(fileId)

    private fun FolderEntity.toDirectoryEntry(): DirectoryEntry = DirectoryEntry(
        id = id,
        name = name,
        kind = EntryKind.FOLDER,
        sizeBytes = 0,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun FileEntity.toDirectoryEntry(): DirectoryEntry = DirectoryEntry(
        id = id,
        name = name,
        kind = EntryKind.FILE,
        sizeBytes = sizeBytes,
        updatedAtEpochMillis = modifiedAtEpochMillis,
        fileStatus = status,
    )

    private fun fail(failure: DriveRepositoryFailure): Nothing =
        throw DriveRepositoryException(failure)

    private companion object {
        const val ROOT_NAME = "我的云盘"
        const val INDEX_FORMAT_VERSION = 1
        const val FILE_DELETION_REASON = "FILE_DELETION"
        val FINAL_DELETION_STATES = setOf(FileStatus.DELETING, FileStatus.PARTIALLY_DELETED)
    }
}
