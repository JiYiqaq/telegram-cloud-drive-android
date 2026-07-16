package com.teledrive.lite.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.teledrive.lite.repository.SetupConnectionService
import com.teledrive.lite.settings.ConfigValidator
import com.teledrive.lite.settings.SecureConfigStore
import com.teledrive.lite.settings.ValidatedConnectionConfig
import com.teledrive.lite.ui.util.SecureWindowEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ConnectionUpdateViewModel(
    private val connectionService: SetupConnectionService,
    private val secureConfigStore: SecureConfigStore,
) : ViewModel() {
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val saved = MutableStateFlow(false)

    fun validateAndSave(token: String, channelText: String) {
        if (busy.value) return
        val channelId = ConfigValidator.parsePrivateChannelId(channelText)
        if (!ConfigValidator.isBotTokenValid(token) || channelId == null) {
            message.value = "Token 或私人频道 Chat ID 格式不正确"
            return
        }
        viewModelScope.launch {
            busy.value = true
            try {
                connectionService.testBot(token)
                connectionService.testChannel(token, channelId)
                secureConfigStore.save(ValidatedConnectionConfig(token, channelId))
                saved.value = true
                message.value = "新连接已验证并保存"
            } catch (_: Exception) {
                message.value = "验证失败，原连接配置保持不变"
            } finally {
                busy.value = false
            }
        }
    }
}

class ConnectionUpdateViewModelFactory(
    private val connectionService: SetupConnectionService,
    private val secureConfigStore: SecureConfigStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ConnectionUpdateViewModel(connectionService, secureConfigStore) as T
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ConnectionUpdateRoute(viewModel: ConnectionUpdateViewModel, onBack: () -> Unit) {
    SecureWindowEffect()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }
    var channelId by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("修改 Telegram 连接") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("更换 Bot 或频道后可能无法访问原文件。应用会先验证机器人、私人频道和管理员权限，验证失败绝不会覆盖当前配置。")
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("新 Bot Token") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = channelId,
                onValueChange = { channelId = it },
                label = { Text("新私人频道 Chat ID") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.validateAndSave(token, channelId) },
                enabled = !busy && !saved,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("验证后保存") }
            if (busy) CircularProgressIndicator()
            message?.let { Text(it) }
        }
    }
}
