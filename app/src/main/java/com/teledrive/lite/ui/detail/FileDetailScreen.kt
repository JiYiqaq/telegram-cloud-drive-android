package com.teledrive.lite.ui.detail

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.teledrive.lite.repository.FileDetail
import com.teledrive.lite.repository.FileRepository
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class FileDetailViewModel(
    private val repository: FileRepository,
) : ViewModel() {
    val detail = MutableStateFlow<FileDetail?>(null)
    val error = MutableStateFlow<String?>(null)

    fun load(fileId: String) {
        viewModelScope.launch {
            try {
                detail.value = repository.getFileDetail(fileId)
            } catch (_: Exception) {
                error.value = "无法读取文件详情"
            }
        }
    }
}

class FileDetailViewModelFactory(
    private val repository: FileRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = FileDetailViewModel(repository) as T
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FileDetailRoute(fileId: String, viewModel: FileDetailViewModel, onBack: () -> Unit) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(fileId) { viewModel.load(fileId) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文件详情") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        val value = detail
        if (value == null) {
            Column(Modifier.padding(padding).padding(20.dp)) {
                if (error == null) CircularProgressIndicator() else Text(checkNotNull(error))
            }
        } else {
            val context = LocalContext.current
            val file = value.file
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(file.name, style = MaterialTheme.typography.headlineSmall)
                DetailLine("MIME 类型", file.mimeType)
                DetailLine("原始大小", Formatter.formatShortFileSize(context, file.sizeBytes))
                DetailLine("SHA-256", file.sha256 ?: "尚未生成")
                DetailLine("分块", "${file.chunkCount}（已记录 ${value.chunks.size}）")
                DetailLine("上传时间", file.uploadedAtEpochMillis.asDate())
                DetailLine("修改时间", file.modifiedAtEpochMillis.asDate())
                DetailLine("所属目录", value.parentFolderName)
                DetailLine("加密格式", "v${file.encryptionFormatVersion} · AES-256-GCM")
                DetailLine("状态", file.status.name)
                Text("Telegram 中只保存随机化分块名和密文，不包含此处显示的原始名称。")
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Text("$label：$value")
}

private fun Long?.asDate(): String = this?.let {
    DateFormat.getDateTimeInstance().format(Date(it))
} ?: "无"
