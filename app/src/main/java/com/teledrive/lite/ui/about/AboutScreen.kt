package com.teledrive.lite.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.teledrive.lite.BuildConfig

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("TeleDrive Lite", style = MaterialTheme.typography.headlineSmall)
            Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text("GitHub：github.com/JiYiqaq/telegram-cloud-drive-android")
            Text("许可证：Apache License 2.0")
            Text("本项目由 OpenAI Codex 根据项目维护者提供的需求协助设计和实现。项目维护者负责需求定义、代码审查、测试、发布和后续维护。")
            Text("本项目不是 Telegram 官方产品，也不是 OpenAI 官方产品。")
            Text("本项目尚未经过正式第三方安全审计。请勿将 Telegram 作为唯一备份；同步密码丢失后无法恢复加密文件。")
            Text("第三方组件许可证与声明见仓库 THIRD_PARTY_NOTICES.md。")
        }
    }
}
