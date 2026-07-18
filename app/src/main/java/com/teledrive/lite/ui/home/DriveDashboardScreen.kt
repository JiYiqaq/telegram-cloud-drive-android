package com.teledrive.lite.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.lite.database.TransferTaskEntity
import com.teledrive.lite.download.DownloadRetryPolicy
import com.teledrive.lite.model.DirectoryEntry
import com.teledrive.lite.model.EntryKind
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.MoveTarget
import com.teledrive.lite.model.SortDirection
import com.teledrive.lite.model.SortMode
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType
import com.teledrive.lite.repository.TransferHistoryPolicy
import com.teledrive.lite.upload.UploadRetryPolicy
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun DriveDashboardRoute(
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
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var section by rememberSaveable { mutableStateOf(HomeSection.FILES) }
    var pendingDownload by remember { mutableStateOf<DirectoryEntry?>(null) }
    var pendingDeletions by remember { mutableStateOf<List<DirectoryEntry>?>(null) }
    var pendingMove by remember { mutableStateOf<List<DirectoryEntry>?>(null) }
    var pendingRename by remember { mutableStateOf<DirectoryEntry?>(null) }
    var selectedEntries by remember { mutableStateOf<Map<String, DirectoryEntry>>(emptyMap()) }
    var creatingFolder by remember { mutableStateOf(false) }
    var batchMode by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }

    val downloadDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) viewModel.enqueueDownload(entry.id, uri)
    }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        viewModel.enqueueUploads(uris)
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        picker.launch(arrayOf("*/*"))
    }
    val launchFilePicker = {
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
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(directory?.folderId, searchQuery) {
        selectedEntries = emptyMap()
        batchMode = false
    }

    pendingDeletions?.let { entries ->
        AlertDialog(
            onDismissRequest = { pendingDeletions = null },
            title = { Text("确认永久删除") },
            text = {
                Text(
                    if (entries.size == 1) {
                        "将永久删除“${entries.single().name}”的 Telegram 加密分块，并更新置顶索引。此操作不可撤销。"
                    } else {
                        "将安全删除所选 ${entries.size} 项。非空文件夹会递归处理，其中的远端分块也会逐项删除。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeletions = null
                        selectedEntries = emptyMap()
                        batchMode = false
                        viewModel.deleteEntries(entries)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("永久删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletions = null }) { Text("取消") }
            },
        )
    }
    pendingMove?.let { entries ->
        DriveMoveTargetDialog(
            itemCount = entries.size,
            targets = viewModel.availableMoveTargets(entries),
            onMove = { targetId ->
                pendingMove = null
                selectedEntries = emptyMap()
                viewModel.moveEntries(entries, targetId)
            },
            onDismiss = { pendingMove = null },
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
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameEntry == null) {
                            viewModel.createFolder(nameInput)
                        } else {
                            viewModel.renameEntry(renameEntry, nameInput)
                        }
                        creatingFolder = false
                        pendingRename = null
                        nameInput = ""
                    },
                    enabled = nameInput.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        creatingFolder = false
                        pendingRename = null
                        nameInput = ""
                    },
                ) { Text("取消") }
            },
        )
    }

    DriveDashboardScreen(
        section = section,
        onSectionChange = { section = it },
        entries = if (searchQuery.isBlank()) directory?.entries.orEmpty() else searchResults,
        breadcrumbs = directory?.breadcrumbs.orEmpty(),
        searchQuery = searchQuery,
        sortMode = sortMode,
        sortDirection = sortDirection,
        transfers = transfers,
        loading = directory == null,
        isEnqueueing = isEnqueueing,
        selectionEnabled = batchMode && searchQuery.isBlank(),
        selectedEntryIds = selectedEntries.keys,
        snackbarHostState = snackbarHostState,
        onAddFile = launchFilePicker,
        onCreateFolder = {
            creatingFolder = true
            nameInput = ""
        },
        onOpenSettings = onOpenSettings,
        onOpenFolder = viewModel::openFolder,
        onNavigateUp = viewModel::navigateUp,
        onSearchQueryChange = viewModel::setSearchQuery,
        onSort = viewModel::setSort,
        onToggleSortDirection = viewModel::toggleSortDirection,
        onDownload = { entry ->
            pendingDownload = entry
            downloadDestination.launch(entry.name)
        },
        onDelete = { pendingDeletions = listOf(it) },
        onRename = {
            pendingRename = it
            nameInput = it.name
        },
        onMove = { pendingMove = listOf(it) },
        onOpenFileDetail = { onOpenFileDetail(it.id) },
        onToggleBatchMode = {
            batchMode = !batchMode
            selectedEntries = emptyMap()
        },
        onToggleSelection = { entry ->
            selectedEntries = selectedEntries.toMutableMap().apply {
                if (remove(entry.id) == null) put(entry.id, entry)
            }
        },
        onSelectAll = {
            selectedEntries = directory?.entries.orEmpty().associateBy(DirectoryEntry::id)
        },
        onClearSelection = { selectedEntries = emptyMap() },
        onBatchMove = { pendingMove = selectedEntries.values.toList() },
        onBatchDelete = { pendingDeletions = selectedEntries.values.toList() },
        onCancelUpload = viewModel::cancelUpload,
        onRetryUpload = viewModel::retryUpload,
        onCleanupUpload = viewModel::cleanupUpload,
        onCancelDownload = viewModel::cancelDownload,
        onRetryDownload = viewModel::retryDownload,
        onDismissTransfer = viewModel::dismissTransfer,
        onClearTransferHistory = viewModel::clearTransferHistory,
        modifier = modifier,
    )
}

@Composable
@Suppress("LongParameterList")
private fun DriveDashboardScreen(
    section: HomeSection,
    onSectionChange: (HomeSection) -> Unit,
    entries: List<DirectoryEntry>,
    breadcrumbs: List<Pair<String, String>>,
    searchQuery: String,
    sortMode: SortMode,
    sortDirection: SortDirection,
    transfers: List<TransferTaskEntity>,
    loading: Boolean,
    isEnqueueing: Boolean,
    selectionEnabled: Boolean,
    selectedEntryIds: Set<String>,
    snackbarHostState: SnackbarHostState,
    onAddFile: () -> Unit,
    onCreateFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSort: (SortMode) -> Unit,
    onToggleSortDirection: () -> Unit,
    onDownload: (DirectoryEntry) -> Unit,
    onDelete: (DirectoryEntry) -> Unit,
    onRename: (DirectoryEntry) -> Unit,
    onMove: (DirectoryEntry) -> Unit,
    onOpenFileDetail: (DirectoryEntry) -> Unit,
    onToggleBatchMode: () -> Unit,
    onToggleSelection: (DirectoryEntry) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onBatchMove: () -> Unit,
    onBatchDelete: () -> Unit,
    onCancelUpload: (String) -> Unit,
    onRetryUpload: (String) -> Unit,
    onCleanupUpload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onDismissTransfer: (String) -> Unit,
    onClearTransferHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var addMenuExpanded by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            DriveBottomNavigation(
                section = section,
                onSectionChange = {
                    addMenuExpanded = false
                    onSectionChange(it)
                },
                onOpenSettings = onOpenSettings,
                transferCount = DriveDashboardPresentation.activeTransfers(transfers).size,
            )
        },
        floatingActionButton = {
            if (section == HomeSection.FILES && !selectionEnabled) {
                DriveAddMenu(
                    expanded = addMenuExpanded,
                    onToggle = { addMenuExpanded = !addMenuExpanded },
                    onUpload = {
                        addMenuExpanded = false
                        onAddFile()
                    },
                    onCreateFolder = {
                        addMenuExpanded = false
                        onCreateFolder()
                    },
                )
            }
        },
    ) { padding ->
        when (section) {
            HomeSection.FILES -> DriveFilesPane(
                entries = entries,
                breadcrumbs = breadcrumbs,
                searchQuery = searchQuery,
                sortMode = sortMode,
                sortDirection = sortDirection,
                loading = loading,
                isEnqueueing = isEnqueueing,
                selectionEnabled = selectionEnabled,
                selectedEntryIds = selectedEntryIds,
                onAddFile = onAddFile,
                onCreateFolder = onCreateFolder,
                onOpenSettings = onOpenSettings,
                onOpenFolder = onOpenFolder,
                onNavigateUp = onNavigateUp,
                onSearchQueryChange = onSearchQueryChange,
                onSort = onSort,
                onToggleSortDirection = onToggleSortDirection,
                onDownload = onDownload,
                onDelete = onDelete,
                onRename = onRename,
                onMove = onMove,
                onOpenFileDetail = onOpenFileDetail,
                onToggleBatchMode = onToggleBatchMode,
                onToggleSelection = onToggleSelection,
                onSelectAll = onSelectAll,
                onClearSelection = onClearSelection,
                onBatchMove = onBatchMove,
                onBatchDelete = onBatchDelete,
                contentPadding = padding,
            )

            HomeSection.TRANSFERS -> DriveTransfersPane(
                transfers = transfers,
                onCancelUpload = onCancelUpload,
                onRetryUpload = onRetryUpload,
                onCleanupUpload = onCleanupUpload,
                onCancelDownload = onCancelDownload,
                onRetryDownload = onRetryDownload,
                onDismissTransfer = onDismissTransfer,
                onClearTransferHistory = onClearTransferHistory,
                contentPadding = padding,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun DriveFilesPane(
    entries: List<DirectoryEntry>,
    breadcrumbs: List<Pair<String, String>>,
    searchQuery: String,
    sortMode: SortMode,
    sortDirection: SortDirection,
    loading: Boolean,
    isEnqueueing: Boolean,
    selectionEnabled: Boolean,
    selectedEntryIds: Set<String>,
    onAddFile: () -> Unit,
    onCreateFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSort: (SortMode) -> Unit,
    onToggleSortDirection: () -> Unit,
    onDownload: (DirectoryEntry) -> Unit,
    onDelete: (DirectoryEntry) -> Unit,
    onRename: (DirectoryEntry) -> Unit,
    onMove: (DirectoryEntry) -> Unit,
    onOpenFileDetail: (DirectoryEntry) -> Unit,
    onToggleBatchMode: () -> Unit,
    onToggleSelection: (DirectoryEntry) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onBatchMove: () -> Unit,
    onBatchDelete: () -> Unit,
    contentPadding: PaddingValues,
) {
    if (loading) {
        Box(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DriveHeroHeader(
                fileCount = entries.size,
                currentFolder = breadcrumbs.lastOrNull()?.second ?: "我的云盘",
                onOpenSettings = onOpenSettings,
            )
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        TextButton(onClick = { onSearchQueryChange("") }) { Text("清除") }
                    }
                },
                placeholder = { Text("搜索文件和文件夹") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            DriveQuickActions(onAddFile = onAddFile, onCreateFolder = onCreateFolder)
        }
        item {
            DriveFolderToolbar(
                breadcrumbs = breadcrumbs,
                sortMode = sortMode,
                sortDirection = sortDirection,
                selectionEnabled = selectionEnabled,
                canManage = searchQuery.isBlank() && entries.isNotEmpty(),
                onNavigateUp = onNavigateUp,
                onSort = onSort,
                onToggleSortDirection = onToggleSortDirection,
                onToggleBatchMode = onToggleBatchMode,
            )
        }
        if (isEnqueueing) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("正在读取文件并加入加密上传队列…")
                    }
                }
            }
        }
        if (selectionEnabled) {
            item {
                DriveSelectionToolbar(
                    selectedCount = selectedEntryIds.size,
                    onSelectAll = onSelectAll,
                    onClearSelection = onClearSelection,
                    onMove = onBatchMove,
                    onDelete = onBatchDelete,
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (searchQuery.isBlank()) "全部文件" else "搜索结果",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${entries.size} 项",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (entries.isEmpty()) {
            item {
                DriveEmptyFiles(
                    searching = searchQuery.isNotBlank(),
                    onAddFile = onAddFile,
                )
            }
        } else {
            items(entries, key = { "drive-entry:${it.id}" }) { entry ->
                DriveEntryRow(
                    entry = entry,
                    selectionEnabled = selectionEnabled,
                    selected = entry.id in selectedEntryIds,
                    onToggleSelection = onToggleSelection,
                    onOpenFolder = onOpenFolder,
                    onDownload = onDownload,
                    onOpenFileDetail = onOpenFileDetail,
                    onRename = onRename,
                    onMove = onMove,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun DriveHeroHeader(
    fileCount: Int,
    currentFolder: String,
    onOpenSettings: () -> Unit,
) {
    val hero = DriveDashboardPresentation.heroPalette(MaterialTheme.colorScheme)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(hero.start, hero.end),
                ),
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = hero.content.copy(alpha = 0.18f),
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("T", color = hero.content, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "TeleDrive 云盘",
                        color = hero.content,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "端到端加密 · Telegram 存储",
                        color = hero.content.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = onOpenSettings) {
                Text("设置", color = hero.content)
            }
        }
        Column {
            Text(
                currentFolder,
                color = hero.content,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$fileCount 个项目 · 文件仅在本机解密",
                color = hero.content.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DriveQuickActions(
    onAddFile: () -> Unit,
    onCreateFolder: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DriveQuickAction(
            glyph = "↑",
            title = "上传文件",
            subtitle = "选择一个或多个",
            onClick = onAddFile,
            modifier = Modifier.weight(1f),
        )
        DriveQuickAction(
            glyph = "+",
            title = "新建文件夹",
            subtitle = "整理云端文件",
            onClick = onCreateFolder,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DriveQuickAction(
    glyph: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DriveGlyph(glyph = glyph, folder = glyph == "+")
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DriveFolderToolbar(
    breadcrumbs: List<Pair<String, String>>,
    sortMode: SortMode,
    sortDirection: SortDirection,
    selectionEnabled: Boolean,
    canManage: Boolean,
    onNavigateUp: () -> Unit,
    onSort: (SortMode) -> Unit,
    onToggleSortDirection: () -> Unit,
    onToggleBatchMode: () -> Unit,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (breadcrumbs.size > 1) {
                TextButton(onClick = onNavigateUp) { Text("‹ 上级") }
            }
            Text(
                breadcrumbs.joinToString(" / ") { it.second }.ifBlank { "我的云盘" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                OutlinedButton(onClick = { sortExpanded = true }) {
                    val sortLabel = when (sortMode) {
                        SortMode.NAME -> "名称"
                        SortMode.SIZE -> "大小"
                        SortMode.UPDATED_AT -> "时间"
                    }
                    Text("$sortLabel · ${if (sortDirection == SortDirection.ASCENDING) "升序" else "降序"}")
                }
                DropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false },
                ) {
                    listOf(
                        SortMode.NAME to "按名称",
                        SortMode.SIZE to "按大小",
                        SortMode.UPDATED_AT to "按时间",
                    ).forEach { (mode, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                sortExpanded = false
                                onSort(mode)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (sortDirection == SortDirection.ASCENDING) {
                                    "切换为降序"
                                } else {
                                    "切换为升序"
                                },
                            )
                        },
                        onClick = {
                            sortExpanded = false
                            onToggleSortDirection()
                        },
                    )
                }
            }
            if (canManage || selectionEnabled) {
                TextButton(onClick = onToggleBatchMode) {
                    Text(if (selectionEnabled) "完成" else "批量管理")
                }
            }
        }
    }
}

@Composable
private fun DriveSelectionToolbar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("已选择 $selectedCount 项", fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSelectAll) { Text("全选") }
                TextButton(onClick = onClearSelection) { Text("清除") }
                TextButton(onClick = onMove, enabled = selectedCount > 0) { Text("移动") }
                TextButton(
                    onClick = onDelete,
                    enabled = selectedCount > 0,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("删除") }
            }
        }
    }
}

@Composable
private fun DriveEntryRow(
    entry: DirectoryEntry,
    selectionEnabled: Boolean,
    selected: Boolean,
    onToggleSelection: (DirectoryEntry) -> Unit,
    onOpenFolder: (String) -> Unit,
    onDownload: (DirectoryEntry) -> Unit,
    onOpenFileDetail: (DirectoryEntry) -> Unit,
    onRename: (DirectoryEntry) -> Unit,
    onMove: (DirectoryEntry) -> Unit,
    onDelete: (DirectoryEntry) -> Unit,
) {
    val context = LocalContext.current
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
            .clickable(enabled = selectionEnabled || opens) {
                when {
                    selectionEnabled -> onToggleSelection(entry)
                    entry.kind == EntryKind.FOLDER -> onOpenFolder(entry.id)
                    downloadable -> onDownload(entry)
                }
            },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionEnabled) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection(entry) },
                )
                Spacer(Modifier.width(4.dp))
            }
            DriveGlyph(
                glyph = if (entry.kind == EntryKind.FOLDER) "▰" else fileGlyph(entry.name),
                folder = entry.kind == EntryKind.FOLDER,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                val metadata = if (entry.kind == EntryKind.FOLDER) {
                    "文件夹"
                } else {
                    "${Formatter.formatShortFileSize(context, entry.sizeBytes)} · " +
                        DateFormat.getDateInstance(DateFormat.SHORT)
                            .format(Date(entry.updatedAtEpochMillis))
                }
                Text(
                    metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (entry.kind == EntryKind.FILE) {
                    val guidance = entry.fileStatus?.let(
                        DriveDashboardPresentation::fileStatusGuidance,
                    )
                    Text(
                        guidance ?: entry.fileStatus?.let(HomePresentation::fileStatusLabel).orEmpty(),
                        color = if (
                            entry.fileStatus in setOf(
                                FileStatus.FAILED,
                                FileStatus.PARTIALLY_DELETED,
                                FileStatus.CORRUPTED,
                            )
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!selectionEnabled) {
                DriveEntryMenu(
                    entry = entry,
                    deletable = deletable,
                    onOpenFileDetail = onOpenFileDetail,
                    onRename = onRename,
                    onMove = onMove,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun DriveEntryMenu(
    entry: DirectoryEntry,
    deletable: Boolean,
    onOpenFileDetail: (DirectoryEntry) -> Unit,
    onRename: (DirectoryEntry) -> Unit,
    onMove: (DirectoryEntry) -> Unit,
    onDelete: (DirectoryEntry) -> Unit,
) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("⋮", style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (entry.kind == EntryKind.FILE) {
                DropdownMenuItem(
                    text = { Text("文件详情") },
                    onClick = {
                        expanded = false
                        onOpenFileDetail(entry)
                    },
                )
            }
            if (deletable) {
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = {
                        expanded = false
                        onRename(entry)
                    },
                )
                DropdownMenuItem(
                    text = { Text("移动到…") },
                    onClick = {
                        expanded = false
                        onMove(entry)
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (entry.fileStatus == FileStatus.PARTIALLY_DELETED) {
                                "重试安全删除"
                            } else {
                                "永久删除"
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        expanded = false
                        onDelete(entry)
                    },
                )
            }
        }
    }
}

@Composable
private fun DriveGlyph(glyph: String, folder: Boolean) {
    Surface(
        color = if (folder) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.size(46.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                glyph,
                color = if (folder) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DriveEmptyFiles(searching: Boolean, onAddFile: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (searching) "⌕" else "☁", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (searching) "没有找到匹配项目" else "这个文件夹还是空的",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (searching) {
                    "换个名称搜索，或清空搜索条件。"
                } else {
                    "上传文件后会先在本机加密，再保存到你的 Telegram 私人频道。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!searching) {
                FilledTonalButton(onClick = onAddFile) { Text("上传第一个文件") }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun DriveTransfersPane(
    transfers: List<TransferTaskEntity>,
    onCancelUpload: (String) -> Unit,
    onRetryUpload: (String) -> Unit,
    onCleanupUpload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onDismissTransfer: (String) -> Unit,
    onClearTransferHistory: () -> Unit,
    contentPadding: PaddingValues,
) {
    val active = DriveDashboardPresentation.activeTransfers(transfers)
    val history = DriveDashboardPresentation.historyTransfers(transfers)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "传输中心",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "上传、下载和历史记录集中管理",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            DriveTransferSummary(
                activeCount = active.size,
                historyCount = history.size,
            )
        }
        if (active.isNotEmpty()) {
            item { DriveSectionHeader(title = "进行中", count = active.size) }
            items(active, key = { "active-transfer:${it.id}" }) { task ->
                DriveTransferCard(
                    task = task,
                    onCancelUpload = onCancelUpload,
                    onRetryUpload = onRetryUpload,
                    onCleanupUpload = onCleanupUpload,
                    onCancelDownload = onCancelDownload,
                    onRetryDownload = onRetryDownload,
                    onDismiss = onDismissTransfer,
                )
            }
        }
        if (history.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DriveSectionHeader(title = "历史记录", count = history.size)
                    TextButton(onClick = onClearTransferHistory) { Text("一键清理") }
                }
            }
            items(history, key = { "history-transfer:${it.id}" }) { task ->
                DriveTransferCard(
                    task = task,
                    onCancelUpload = onCancelUpload,
                    onRetryUpload = onRetryUpload,
                    onCleanupUpload = onCleanupUpload,
                    onCancelDownload = onCancelDownload,
                    onRetryDownload = onRetryDownload,
                    onDismiss = onDismissTransfer,
                )
            }
        }
        if (transfers.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("↕", style = MaterialTheme.typography.headlineMedium)
                        Text("暂无传输任务", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "上传或下载文件后，进度会显示在这里。清理历史记录不会删除云端文件。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveTransferSummary(activeCount: Int, historyCount: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("$activeCount", style = MaterialTheme.typography.headlineMedium)
                Text("正在传输", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("$historyCount", style = MaterialTheme.typography.headlineMedium)
                Text("历史记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DriveSectionHeader(title: String, count: Int) {
    Text(
        "$title · $count",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
@Suppress("LongParameterList")
private fun DriveTransferCard(
    task: TransferTaskEntity,
    onCancelUpload: (String) -> Unit,
    onRetryUpload: (String) -> Unit,
    onCleanupUpload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    val context = LocalContext.current
    val progress = if (task.totalBytes == 0L) {
        if (task.currentChunk == task.totalChunks) 1f else 0f
    } else {
        (task.completedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DriveGlyph(
                    glyph = if (task.type == TransferType.UPLOAD) "↑" else "↓",
                    folder = task.type == TransferType.UPLOAD,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.fileNameSnapshot,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${HomePresentation.transferTypeLabel(task.type)} · " +
                            HomePresentation.transferStatusLabel(task.status),
                        color = statusColor(task.status),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (TransferHistoryPolicy.canDismiss(task)) {
                    TextButton(onClick = { onDismiss(task.id) }) { Text("删除记录") }
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${(progress * 100).roundToInt()}% · 分块 ${task.currentChunk}/${task.totalChunks}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${Formatter.formatShortFileSize(context, task.completedBytes)} / " +
                        Formatter.formatShortFileSize(context, task.totalBytes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (task.speedBytesPerSecond > 0) {
                Text(
                    "速度 ${Formatter.formatShortFileSize(context, task.speedBytesPerSecond)}/秒",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            task.errorCode?.let {
                Text(
                    "失败原因：$it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (task.type == TransferType.UPLOAD) {
                    if (UploadRetryPolicy.canCancel(task)) {
                        TextButton(onClick = { onCancelUpload(task.id) }) { Text("取消") }
                    }
                    if (UploadRetryPolicy.canRetry(task)) {
                        TextButton(onClick = { onRetryUpload(task.id) }) { Text("重试") }
                    }
                    if (task.status in setOf(TransferStatus.CANCELED, TransferStatus.FAILED)) {
                        TextButton(onClick = { onCleanupUpload(task.id) }) {
                            Text("清理远端分块")
                        }
                    }
                } else {
                    if (DownloadRetryPolicy.canCancel(task)) {
                        TextButton(onClick = { onCancelDownload(task.id) }) { Text("取消") }
                    }
                    if (DownloadRetryPolicy.canRetry(task)) {
                        TextButton(onClick = { onRetryDownload(task.id) }) { Text("重试") }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: TransferStatus): Color = when (status) {
    TransferStatus.SUCCESS -> MaterialTheme.colorScheme.primary
    TransferStatus.FAILED -> MaterialTheme.colorScheme.error
    TransferStatus.CANCELED -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun DriveBottomNavigation(
    section: HomeSection,
    onSectionChange: (HomeSection) -> Unit,
    onOpenSettings: () -> Unit,
    transferCount: Int,
) {
    NavigationBar {
        NavigationBarItem(
            selected = section == HomeSection.FILES,
            onClick = { onSectionChange(HomeSection.FILES) },
            icon = { Text("☁") },
            label = { Text("文件") },
        )
        NavigationBarItem(
            selected = section == HomeSection.TRANSFERS,
            onClick = { onSectionChange(HomeSection.TRANSFERS) },
            icon = { Text(if (transferCount > 0) "↕ $transferCount" else "↕") },
            label = { Text("传输") },
        )
        NavigationBarItem(
            selected = false,
            onClick = onOpenSettings,
            icon = { Text("⚙") },
            label = { Text("设置") },
        )
    }
}

@Composable
private fun DriveAddMenu(
    expanded: Boolean,
    onToggle: () -> Unit,
    onUpload: () -> Unit,
    onCreateFolder: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (expanded) {
            Card(
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    TextButton(onClick = onUpload) { Text("↑  上传文件") }
                    TextButton(onClick = onCreateFolder) { Text("+  新建文件夹") }
                }
            }
        }
        FloatingActionButton(onClick = onToggle) {
            Text(if (expanded) "×" else "+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun DriveMoveTargetDialog(
    itemCount: Int,
    targets: List<MoveTarget>,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动 $itemCount 项到") },
        text = {
            if (targets.isEmpty()) {
                Text("没有可用的目标文件夹")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(targets, key = MoveTarget::id) { target ->
                        TextButton(
                            onClick = { onMove(target.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(target.path, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun fileGlyph(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg", "png", "gif", "webp" -> "图"
        "mp4", "mkv", "mov", "avi" -> "影"
        "mp3", "wav", "flac", "m4a" -> "音"
        "pdf" -> "PDF"
        "zip", "rar", "7z", "tar", "gz" -> "压"
        else -> "文"
    }
}
