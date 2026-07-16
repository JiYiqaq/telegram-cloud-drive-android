package com.teledrive.lite.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.teledrive.lite.R
import com.teledrive.lite.database.TransferTaskEntity
import com.teledrive.lite.model.DirectoryEntry
import com.teledrive.lite.model.EntryKind
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.TransferType
import com.teledrive.lite.model.SortMode
import com.teledrive.lite.model.SortDirection
import com.teledrive.lite.download.DownloadRetryPolicy
import com.teledrive.lite.upload.UploadRetryPolicy
import kotlin.math.roundToInt
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    onOpenFileDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val directory by viewModel.directory.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val sortDirection by viewModel.sortDirection.collectAsStateWithLifecycle()
    val isEnqueueing by viewModel.isEnqueueing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingDownload by remember { mutableStateOf<DirectoryEntry?>(null) }
    var pendingDeletion by remember { mutableStateOf<DirectoryEntry?>(null) }
    var pendingRename by remember { mutableStateOf<DirectoryEntry?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    val downloadDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) viewModel.enqueueDownload(entry.id, uri)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.enqueueUploads(uris)
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        picker.launch(arrayOf("*/*"))
    }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    pendingDeletion?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = { Text(stringResource(R.string.confirm_delete_message, entry.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeletion = null
                        viewModel.deleteEntry(entry)
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (creatingFolder || pendingRename != null) {
        val renameEntry = pendingRename
        AlertDialog(
            onDismissRequest = {
                creatingFolder = false
                pendingRename = null
                nameInput = ""
            },
            title = { Text(if (renameEntry == null) "新建文件夹" else "重命名") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameEntry == null) viewModel.createFolder(nameInput)
                        else viewModel.renameEntry(renameEntry, nameInput)
                        creatingFolder = false
                        pendingRename = null
                        nameInput = ""
                    },
                    enabled = nameInput.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    creatingFolder = false
                    pendingRename = null
                    nameInput = ""
                }) { Text("取消") }
            },
        )
    }
    HomeScreen(
        entries = if (searchQuery.isBlank()) directory?.entries.orEmpty() else searchResults,
        breadcrumbs = directory?.breadcrumbs.orEmpty(),
        searchQuery = searchQuery,
        sortMode = sortMode,
        sortDirection = sortDirection,
        transfers = transfers,
        loading = directory == null,
        isEnqueueing = isEnqueueing,
        onAddFile = {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                picker.launch(arrayOf("*/*"))
            }
        },
        onCancelUpload = viewModel::cancelUpload,
        onRetryUpload = viewModel::retryUpload,
        onCleanupUpload = viewModel::cleanupUpload,
        onDownload = { entry ->
            pendingDownload = entry
            downloadDestination.launch(entry.name)
        },
        onCancelDownload = viewModel::cancelDownload,
        onRetryDownload = viewModel::retryDownload,
        onDelete = { pendingDeletion = it },
        onOpenFolder = viewModel::openFolder,
        onNavigateUp = viewModel::navigateUp,
        onSearchQueryChange = viewModel::setSearchQuery,
        onSort = viewModel::setSort,
        onToggleSortDirection = viewModel::toggleSortDirection,
        onCreateFolder = {
            creatingFolder = true
            nameInput = ""
        },
        onRename = {
            pendingRename = it
            nameInput = it.name
        },
        onMoveToRoot = viewModel::moveEntryToRoot,
        onOpenFileDetail = { onOpenFileDetail(it.id) },
        snackbarHostState = snackbarHostState,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HomeScreen(
    entries: List<DirectoryEntry>,
    breadcrumbs: List<Pair<String, String>>,
    searchQuery: String,
    sortMode: SortMode,
    sortDirection: SortDirection,
    transfers: List<TransferTaskEntity>,
    loading: Boolean,
    isEnqueueing: Boolean,
    onAddFile: () -> Unit,
    onCancelUpload: (String) -> Unit,
    onRetryUpload: (String) -> Unit,
    onCleanupUpload: (String) -> Unit,
    onDownload: (DirectoryEntry) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onDelete: (DirectoryEntry) -> Unit,
    onOpenFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSort: (SortMode) -> Unit,
    onToggleSortDirection: () -> Unit,
    onCreateFolder: () -> Unit,
    onRename: (DirectoryEntry) -> Unit,
    onMoveToRoot: (DirectoryEntry) -> Unit,
    onOpenFileDetail: (DirectoryEntry) -> Unit,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                navigationIcon = {
                    if (breadcrumbs.size > 1) TextButton(onClick = onNavigateUp) { Text("上级") }
                },
                actions = {
                    TextButton(onClick = onCreateFolder) { Text("新建") }
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFile) {
                Text(stringResource(R.string.add_file_symbol))
            }
        },
    ) { padding ->
        when {
            loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        breadcrumbs.joinToString(" / ") { it.second }.ifBlank { "我的云盘" },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        label = { Text("搜索文件和文件夹") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            SortMode.NAME to "名称",
                            SortMode.SIZE to "大小",
                            SortMode.UPDATED_AT to "时间",
                        ).forEach { (mode, label) ->
                            TextButton(onClick = { onSort(mode) }) {
                                Text(if (sortMode == mode) "[$label]" else label)
                            }
                        }
                        TextButton(onClick = onToggleSortDirection) {
                            Text(if (sortDirection == SortDirection.ASCENDING) "升序" else "降序")
                        }
                    }
                }
                if (transfers.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.transfer_queue),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(transfers, key = { "transfer:${it.id}" }) { transfer ->
                        TransferCard(
                            task = transfer,
                            onCancelUpload = onCancelUpload,
                            onRetryUpload = onRetryUpload,
                            onCleanupUpload = onCleanupUpload,
                            onCancelDownload = onCancelDownload,
                            onRetryDownload = onRetryDownload,
                        )
                    }
                }
                item {
                    Text(
                        stringResource(R.string.files_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.home_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(entries, key = { "entry:${it.id}" }) { entry ->
                        EntryCard(
                            entry,
                            onDownload,
                            onDelete,
                            onOpenFolder,
                            onRename,
                            onMoveToRoot,
                            onOpenFileDetail,
                            breadcrumbs.size > 1,
                        )
                    }
                }
                if (isEnqueueing) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                            Text(stringResource(R.string.enqueueing_upload))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: DirectoryEntry,
    onDownload: (DirectoryEntry) -> Unit,
    onDelete: (DirectoryEntry) -> Unit,
    onOpenFolder: (String) -> Unit,
    onRename: (DirectoryEntry) -> Unit,
    onMoveToRoot: (DirectoryEntry) -> Unit,
    onOpenFileDetail: (DirectoryEntry) -> Unit,
    canMoveToRoot: Boolean,
) {
    val downloadable = entry.kind == EntryKind.FILE &&
        entry.fileStatus in setOf(FileStatus.AVAILABLE, FileStatus.CORRUPTED)
    val deletable = entry.kind == EntryKind.FOLDER || entry.fileStatus in setOf(
        FileStatus.AVAILABLE,
        FileStatus.CORRUPTED,
        FileStatus.FAILED,
        FileStatus.PARTIALLY_DELETED,
    )
    val opens = entry.kind == EntryKind.FOLDER || downloadable
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = opens) {
                if (entry.kind == EntryKind.FOLDER) onOpenFolder(entry.id) else onDownload(entry)
            },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            Text(
                if (entry.kind == EntryKind.FOLDER) {
                    stringResource(R.string.folder_label)
                } else {
                    entry.fileStatus?.name.orEmpty()
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.kind == EntryKind.FILE) {
                val context = LocalContext.current
                Text(
                    "${Formatter.formatShortFileSize(context, entry.sizeBytes)} · " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(entry.updatedAtEpochMillis)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (downloadable) {
                Text(
                    stringResource(R.string.tap_to_download),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (entry.kind == EntryKind.FILE && deletable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onOpenFileDetail(entry) }) { Text("详情") }
                    TextButton(onClick = { onRename(entry) }) { Text("重命名") }
                    if (canMoveToRoot) {
                        TextButton(onClick = { onMoveToRoot(entry) }) { Text("移到根目录") }
                    }
                    TextButton(onClick = { onDelete(entry) }) {
                        Text(
                            stringResource(
                                if (entry.fileStatus == FileStatus.PARTIALLY_DELETED) {
                                    R.string.retry_safe_delete
                                } else {
                                    R.string.delete
                                },
                            ),
                        )
                    }
                }
            } else if (entry.kind == EntryKind.FOLDER) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onRename(entry) }) { Text("重命名") }
                    if (canMoveToRoot) {
                        TextButton(onClick = { onMoveToRoot(entry) }) { Text("移到根目录") }
                    }
                    TextButton(onClick = { onDelete(entry) }) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun TransferCard(
    task: TransferTaskEntity,
    onCancelUpload: (String) -> Unit,
    onRetryUpload: (String) -> Unit,
    onCleanupUpload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
) {
    val context = LocalContext.current
    val progress = if (task.totalBytes == 0L) {
        if (task.currentChunk == task.totalChunks) 1f else 0f
    } else {
        (task.completedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(task.fileNameSnapshot, style = MaterialTheme.typography.titleSmall)
                Text(task.status.name)
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(
                    R.string.transfer_progress,
                    (progress * 100).roundToInt(),
                    task.currentChunk,
                    task.totalChunks,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.transfer_bytes,
                    Formatter.formatShortFileSize(context, task.completedBytes),
                    Formatter.formatShortFileSize(context, task.totalBytes),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (task.speedBytesPerSecond > 0) {
                Text(
                    stringResource(
                        R.string.transfer_speed,
                        Formatter.formatShortFileSize(context, task.speedBytesPerSecond),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task.errorCode?.let { errorCode ->
                Text(
                    stringResource(R.string.transfer_error, errorCode),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (task.type == TransferType.UPLOAD) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (UploadRetryPolicy.canCancel(task)) {
                        TextButton(onClick = { onCancelUpload(task.id) }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                    if (UploadRetryPolicy.canRetry(task)) {
                        TextButton(onClick = { onRetryUpload(task.id) }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                    if (task.status in setOf(
                            com.teledrive.lite.model.TransferStatus.CANCELED,
                            com.teledrive.lite.model.TransferStatus.FAILED,
                        )
                    ) {
                        TextButton(onClick = { onCleanupUpload(task.id) }) {
                            Text(stringResource(R.string.cleanup_remote_chunks))
                        }
                    }
                }
            } else if (task.type == TransferType.DOWNLOAD) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (DownloadRetryPolicy.canCancel(task)) {
                        TextButton(onClick = { onCancelDownload(task.id) }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                    if (DownloadRetryPolicy.canRetry(task)) {
                        TextButton(onClick = { onRetryDownload(task.id) }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        }
    }
}
