package com.teledrive.lite.ui.recovery

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.teledrive.lite.sync.IndexRecoveryService
import com.teledrive.lite.ui.util.SecureWindowEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class RecoveryViewModel(
    private val serviceFactory: () -> IndexRecoveryService?,
) : ViewModel() {
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val recovered = MutableStateFlow(false)

    fun recover(password: CharArray) {
        if (busy.value || password.isEmpty()) return
        viewModelScope.launch {
            busy.value = true
            try {
                val result = requireNotNull(serviceFactory()).recover(password)
                message.value = "已恢复 revision ${(result as com.teledrive.lite.sync.IndexRecoveryOutcome.Recovered).revision}"
                recovered.value = true
            } catch (_: Exception) {
                password.fill('\u0000')
                message.value = "恢复失败：请检查同步密码、置顶索引和本地未完成任务"
            } finally {
                busy.value = false
            }
        }
    }
}

class RecoveryViewModelFactory(
    private val serviceFactory: () -> IndexRecoveryService?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RecoveryViewModel(serviceFactory) as T
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RecoveryRoute(viewModel: RecoveryViewModel, onBack: () -> Unit, onRecovered: () -> Unit) {
    SecureWindowEffect()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val recovered by viewModel.recovered.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    LaunchedEffect(recovered) { if (recovered) onRecovered() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("从置顶索引恢复") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("输入创建云盘时使用的同一同步密码。恢复前会验证索引格式、认证标签和两次置顶指针；失败不会覆盖本地缓存。")
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("同步密码") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    viewModel.recover(chars)
                },
                enabled = !busy && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("验证并恢复") }
            if (busy) CircularProgressIndicator()
            message?.let { Text(it) }
        }
    }
}
