package com.teledrive.lite.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teledrive.lite.model.DirectorySnapshot
import com.teledrive.lite.model.DirectoryEntry
import com.teledrive.lite.model.EntryKind
import com.teledrive.lite.model.MoveTarget
import com.teledrive.lite.download.DownloadScheduler
import com.teledrive.lite.deletion.DeletionScheduler
import com.teledrive.lite.deletion.DeletionStartRecovery
import com.teledrive.lite.deletion.OrphanCleanupScheduler
import com.teledrive.lite.deletion.FolderDeletionScheduler
import com.teledrive.lite.model.SortDirection
import com.teledrive.lite.model.SortMode
import com.teledrive.lite.repository.FileRepository
import com.teledrive.lite.repository.FolderTreeValidator
import com.teledrive.lite.repository.MoveTargetResolver
import com.teledrive.lite.repository.TransferRepository
import com.teledrive.lite.transfer.StreamingChunker
import com.teledrive.lite.settings.AppPreferences
import com.teledrive.lite.sync.IndexAtomicUpdater
import com.teledrive.lite.upload.UploadScheduler
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val fileRepository: FileRepository,
    private val transferRepository: TransferRepository,
    private val uploadScheduler: UploadScheduler,
    private val downloadScheduler: DownloadScheduler,
    private val deletionScheduler: DeletionScheduler,
    private val orphanCleanupScheduler: OrphanCleanupScheduler,
    private val preferences: AppPreferences,
    private val indexUpdater: () -> IndexAtomicUpdater?,
    private val folderDeletionScheduler: FolderDeletionScheduler,
) : ViewModel() {
    val currentFolderId = MutableStateFlow(FolderTreeValidator.ROOT_ID)
    val sortMode = MutableStateFlow(SortMode.NAME)
    val sortDirection = MutableStateFlow(SortDirection.ASCENDING)
    val searchQuery = MutableStateFlow("")
    val directory = combine(currentFolderId, sortMode, sortDirection) { folder, mode, direction ->
        Triple(folder, mode, direction)
    }.flatMapLatest { (folder, mode, direction) ->
        fileRepository.observeDirectory(folder, mode, direction)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )
    val searchResults = searchQuery.flatMapLatest(fileRepository::search).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val transfers = transferRepository.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    private val folderDescriptors = fileRepository.observeFolderDescriptors().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    val isEnqueueing = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            uploadScheduler.refreshNetworkState()
            downloadScheduler.refreshNetworkState()
            fileRepository.initializeRoot()
        }
    }

    fun enqueueUploads(uris: List<Uri>) {
        if (isEnqueueing.value || uris.isEmpty()) return
        viewModelScope.launch {
            isEnqueueing.value = true
            var queued = 0
            uris.forEach { uri ->
                runCatching {
                    uploadScheduler.enqueue(
                        uri = uri,
                        parentFolderId = currentFolderId.value,
                        chunkSizeBytes = preferences.defaultChunkSizeBytes.value,
                    )
                }.onSuccess { queued += 1 }
            }
            message.value = when {
                queued == uris.size -> "已将 $queued 个文件加入上传队列"
                queued > 0 -> "已加入 $queued 个文件，${uris.size - queued} 个文件失败"
                else -> "无法加入上传队列，请检查文件权限和文件名"
            }
            isEnqueueing.value = false
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    fun cancelUpload(taskId: String) {
        viewModelScope.launch {
            message.value = try {
                uploadScheduler.cancel(taskId)
                "上传已取消，已完成的分块会保留以便重试"
            } catch (_: Exception) {
                "无法取消此上传任务"
            }
        }
    }

    fun retryUpload(taskId: String) {
        viewModelScope.launch {
            message.value = try {
                uploadScheduler.retry(taskId)
                "上传已重新加入队列"
            } catch (_: Exception) {
                "此任务暂时无法安全重试"
            }
        }
    }

    fun enqueueDownload(fileId: String, destinationUri: Uri) {
        viewModelScope.launch {
            message.value = try {
                downloadScheduler.enqueue(fileId, destinationUri)
                "文件已加入下载队列"
            } catch (_: Exception) {
                "无法开始下载，请检查文件状态和保存位置"
            }
        }
    }

    fun cancelDownload(taskId: String) {
        viewModelScope.launch {
            message.value = try {
                downloadScheduler.cancel(taskId)
                "下载已取消，未完成的输出已清空"
            } catch (_: Exception) {
                "无法取消此下载任务"
            }
        }
    }

    fun retryDownload(taskId: String) {
        viewModelScope.launch {
            message.value = try {
                downloadScheduler.retry(taskId)
                "下载已从头重新加入队列"
            } catch (_: Exception) {
                "此任务无法重试，请重新选择文件"
            }
        }
    }

    fun dismissTransfer(taskId: String) {
        viewModelScope.launch {
            message.value = try {
                transferRepository.dismissTerminal(taskId)
                "已删除传输记录；云端文件不受影响"
            } catch (_: Exception) {
                "进行中的任务不能直接删除，请先取消传输"
            }
        }
    }

    fun clearTransferHistory() {
        viewModelScope.launch {
            val removed = transferRepository.clearTerminalHistory()
            message.value = if (removed == 0) {
                "没有可清理的传输记录"
            } else {
                "已清理 $removed 条传输记录；云端文件不受影响"
            }
        }
    }

    fun deleteFile(fileId: String) {
        viewModelScope.launch {
            message.value = try {
                deletionScheduler.enqueue(fileId)
                HomePresentation.deletionMessage(queuedFiles = 1, queuedFolders = 0, failed = 0)
            } catch (_: Exception) {
                "无法开始删除；若为部分删除文件，请稍后重试"
            }
        }
    }

    fun deleteEntry(entry: DirectoryEntry) {
        deleteEntries(listOf(entry))
    }

    fun deleteEntries(entries: List<DirectoryEntry>) {
        val distinctEntries = entries.distinctBy(DirectoryEntry::id)
        if (distinctEntries.isEmpty()) return
        viewModelScope.launch {
            var queuedFiles = 0
            var queuedFolders = 0
            distinctEntries.forEach { entry ->
                runCatching {
                    if (entry.kind == EntryKind.FILE) {
                        DeletionStartRecovery.run(
                            enqueue = { deletionScheduler.enqueue(entry.id) },
                            synchronizeIndex = {
                                requireNotNull(indexUpdater()).resumeOrStart()
                            },
                        )
                    } else {
                        folderDeletionScheduler.enqueue(entry.id)
                    }
                }.onSuccess {
                    if (entry.kind == EntryKind.FILE) queuedFiles += 1 else queuedFolders += 1
                }
            }
            message.value = HomePresentation.deletionMessage(
                queuedFiles = queuedFiles,
                queuedFolders = queuedFolders,
                failed = distinctEntries.size - queuedFiles - queuedFolders,
            )
        }
    }

    fun cleanupUpload(taskId: String) {
        message.value = try {
            orphanCleanupScheduler.enqueue(taskId)
            "孤立分块清理已加入队列"
        } catch (_: Exception) {
            "无法清理此上传的远端分块"
        }
    }

    fun openFolder(folderId: String) {
        currentFolderId.value = folderId
        searchQuery.value = ""
    }

    fun navigateUp() {
        val parent = directory.value?.breadcrumbs?.dropLast(1)?.lastOrNull()?.first
        if (parent != null) currentFolderId.value = parent
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query.take(100)
    }

    fun setSort(mode: SortMode) {
        sortMode.value = mode
    }

    fun toggleSortDirection() {
        sortDirection.value = if (sortDirection.value == SortDirection.ASCENDING) {
            SortDirection.DESCENDING
        } else {
            SortDirection.ASCENDING
        }
    }

    fun createFolder(name: String) = mutateAndPublish("文件夹已创建") {
        fileRepository.createFolder(currentFolderId.value, name)
    }

    fun renameEntry(entry: DirectoryEntry, name: String) = mutateAndPublish("已重命名") {
        if (entry.kind == EntryKind.FOLDER) {
            fileRepository.renameFolder(entry.id, name)
        } else {
            fileRepository.renameFile(entry.id, name)
        }
    }

    fun availableMoveTargets(entries: List<DirectoryEntry>): List<MoveTarget> =
        MoveTargetResolver.resolve(
            folderDescriptors.value,
            entries.asSequence()
                .filter { it.kind == EntryKind.FOLDER }
                .map(DirectoryEntry::id)
                .toSet(),
        )

    fun moveEntries(entries: List<DirectoryEntry>, targetFolderId: String) {
        val distinctEntries = entries.distinctBy(DirectoryEntry::id)
        if (distinctEntries.isEmpty()) return
        viewModelScope.launch {
            var succeeded = 0
            distinctEntries.filter { it.kind == EntryKind.FOLDER }.forEach { folder ->
                runCatching { fileRepository.moveFolder(folder.id, targetFolderId) }
                    .onSuccess { succeeded += 1 }
            }
            val fileIds = distinctEntries.filter { it.kind == EntryKind.FILE }.map(DirectoryEntry::id)
            if (fileIds.isNotEmpty()) {
                succeeded += fileRepository.moveFiles(fileIds, targetFolderId).count { it.succeeded }
            }
            val failed = distinctEntries.size - succeeded
            val indexPublished = if (succeeded > 0) {
                runCatching { requireNotNull(indexUpdater()).resumeOrStart() }.isSuccess
            } else {
                true
            }
            message.value = when {
                succeeded == 0 -> "没有项目被移动，请检查目标目录和名称冲突"
                failed == 0 && indexPublished -> "已移动 $succeeded 项"
                failed == 0 -> "已移动 $succeeded 项；云端索引将在稍后重试同步"
                indexPublished -> "已移动 $succeeded 项，$failed 项失败"
                else -> "已移动 $succeeded 项，$failed 项失败；云端索引待重试"
            }
        }
    }

    private fun mutateAndPublish(success: String, mutation: suspend () -> Unit) {
        viewModelScope.launch {
            message.value = try {
                mutation()
                requireNotNull(indexUpdater()).resumeOrStart()
                success
            } catch (_: Exception) {
                "操作未完成；本地变更会保留并可在设置中重试同步"
            }
        }
    }
}

class HomeViewModelFactory(
    private val fileRepository: FileRepository,
    private val transferRepository: TransferRepository,
    private val uploadScheduler: UploadScheduler,
    private val downloadScheduler: DownloadScheduler,
    private val deletionScheduler: DeletionScheduler,
    private val orphanCleanupScheduler: OrphanCleanupScheduler,
    private val preferences: AppPreferences,
    private val indexUpdater: () -> IndexAtomicUpdater?,
    private val folderDeletionScheduler: FolderDeletionScheduler,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HomeViewModel::class.java))
        return HomeViewModel(
            fileRepository,
            transferRepository,
            uploadScheduler,
            downloadScheduler,
            deletionScheduler,
            orphanCleanupScheduler,
            preferences,
            indexUpdater,
            folderDeletionScheduler,
        ) as T
    }
}
