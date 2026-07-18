package com.teledrive.lite.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.lite.settings.SettingsRepository
import com.teledrive.lite.settings.ThemeMode
import java.text.DateFormat
import java.util.Date

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsRoute(
    viewModel: SettingsViewModel,
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    onRecovery: () -> Unit,
    onConnection: () -> Unit,
    onAbout: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val index by viewModel.indexSummary.collectAsStateWithLifecycle()
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val chunkSize by viewModel.chunkSizeBytes.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val loggedOut by viewModel.logoutCompleted.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val diagnostics = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { viewModel.exportDiagnostics(it, settingsRepository) } }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }
    LaunchedEffect(loggedOut) { if (loggedOut) onLoggedOut() }
    if (confirmClear) ConfirmDialog(
        title = "清理本地缓存？",
        message = "会移除本机目录和任务缓存，不会删除 Telegram 文件或凭据。之后需从置顶索引恢复。",
        onConfirm = { confirmClear = false; viewModel.clearLocalCache() },
        onDismiss = { confirmClear = false },
    )
    if (confirmLogout) ConfirmDialog(
        title = "退出并清除凭据？",
        message = "将取消后台任务，清除 Token、Keystore 会话密钥和本地数据库。Telegram 频道中的加密文件不会被删除。",
        onConfirm = { confirmLogout = false; viewModel.logout() },
        onDismiss = { confirmLogout = false },
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("云端连接", style = MaterialTheme.typography.titleMedium)
            SettingsButton("测试机器人和私人频道", busy, viewModel::testConnections)
            SettingsButton("修改 Bot Token 或 Chat ID", busy, onConnection)
            Text("索引 revision：${index.revision} · ${index.status}")
            Text(
                "上次同步：" + (index.lastSyncedAtEpochMillis?.let {
                    DateFormat.getDateTimeInstance().format(Date(it))
                } ?: "尚未同步"),
            )
            SettingsButton("立即同步加密索引", busy, viewModel::syncIndex)
            SettingsButton("从置顶索引恢复", busy, onRecovery)

            Text("默认分块大小", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsPresentation.chunkSizeRows().forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { mib ->
                            TextButton(onClick = { viewModel.setChunkSize(mib * 1024 * 1024) }) {
                                Text(if (chunkSize == mib * 1024 * 1024) "[$mib MiB]" else "$mib MiB")
                            }
                        }
                    }
                }
            }
            Text("主题", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeMode.entries.forEach { mode ->
                    TextButton(onClick = { viewModel.setTheme(mode) }) {
                        Text(if (theme == mode) "[${mode.label()}]" else mode.label())
                    }
                }
            }
            Text("维护与隐私", style = MaterialTheme.typography.titleMedium)
            SettingsButton("导出脱敏诊断信息", busy) { diagnostics.launch("teledrive-diagnostics.txt") }
            SettingsButton("清理本地缓存", busy) { confirmClear = true }
            SettingsButton("关于与开源许可证", busy, onAbout)
            SettingsButton("退出并清除凭据", busy) { confirmLogout = true }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun SettingsButton(label: String, disabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = !disabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(message) },
    confirmButton = { TextButton(onClick = onConfirm) { Text("确认") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
)

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}
