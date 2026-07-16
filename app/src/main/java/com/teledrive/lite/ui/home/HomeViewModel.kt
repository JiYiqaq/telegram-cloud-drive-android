package com.teledrive.lite.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teledrive.lite.model.DirectorySnapshot
import com.teledrive.lite.model.SortDirection
import com.teledrive.lite.model.SortMode
import com.teledrive.lite.repository.FileRepository
import com.teledrive.lite.repository.FolderTreeValidator
import com.teledrive.lite.repository.TransferRepository
import com.teledrive.lite.transfer.StreamingChunker
import com.teledrive.lite.upload.UploadScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val fileRepository: FileRepository,
    transferRepository: TransferRepository,
    private val uploadScheduler: UploadScheduler,
) : ViewModel() {
    val directory = rootDirectoryFlow().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )
    val transfers = transferRepository.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val isEnqueueing = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    fun enqueueUploads(uris: List<Uri>) {
        if (isEnqueueing.value || uris.isEmpty()) return
        viewModelScope.launch {
            isEnqueueing.value = true
            var queued = 0
            uris.forEach { uri ->
                runCatching {
                    uploadScheduler.enqueue(
                        uri = uri,
                        parentFolderId = FolderTreeValidator.ROOT_ID,
                        chunkSizeBytes = StreamingChunker.DEFAULT_CHUNK_SIZE_BYTES,
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

    private fun rootDirectoryFlow(): Flow<DirectorySnapshot> = flow {
        uploadScheduler.refreshNetworkState()
        fileRepository.initializeRoot()
        emitAll(
            fileRepository.observeDirectory(
                folderId = FolderTreeValidator.ROOT_ID,
                sortMode = SortMode.NAME,
                sortDirection = SortDirection.ASCENDING,
            ),
        )
    }
}

class HomeViewModelFactory(
    private val fileRepository: FileRepository,
    private val transferRepository: TransferRepository,
    private val uploadScheduler: UploadScheduler,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HomeViewModel::class.java))
        return HomeViewModel(fileRepository, transferRepository, uploadScheduler) as T
    }
}
