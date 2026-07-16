package com.teledrive.lite.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teledrive.lite.app.AppMaintenanceService
import com.teledrive.lite.repository.SetupConnectionService
import com.teledrive.lite.settings.AppPreferences
import com.teledrive.lite.settings.IndexSummary
import com.teledrive.lite.settings.SecureConfigStore
import com.teledrive.lite.settings.SettingsRepository
import com.teledrive.lite.settings.ThemeMode
import com.teledrive.lite.sync.IndexAtomicUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    settingsRepository: SettingsRepository,
    val preferences: AppPreferences,
    private val secureConfigStore: SecureConfigStore,
    private val connectionService: SetupConnectionService,
    private val indexUpdater: () -> IndexAtomicUpdater?,
    private val maintenanceService: AppMaintenanceService,
    private val contentResolver: ContentResolver,
) : ViewModel() {
    val indexSummary = settingsRepository.observeIndexSummary().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        com.teledrive.lite.settings.IndexSummary(
            0,
            null,
            com.teledrive.lite.model.IndexSyncStatus.NOT_INITIALIZED,
        ),
    )
    val themeMode = preferences.themeMode
    val chunkSizeBytes = preferences.defaultChunkSizeBytes
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val logoutCompleted = MutableStateFlow(false)

    fun setTheme(mode: ThemeMode) = preferences.setThemeMode(mode)

    fun setChunkSize(bytes: Int) = preferences.setDefaultChunkSizeBytes(bytes)

    fun testConnections() = launchAction {
        val config = requireNotNull(secureConfigStore.load())
        val bot = connectionService.testBot(config.botToken)
        connectionService.testChannel(config.botToken, config.channelId)
        "连接正常：${bot.displayName}"
    }

    fun syncIndex() = launchAction {
        val outcome = requireNotNull(indexUpdater()).resumeOrStart()
        "索引已同步到 revision ${outcome.stableState.revision}"
    }

    fun clearLocalCache() = launchAction {
        maintenanceService.clearLocalCache()
        "本地缓存已清理；可从置顶索引恢复"
    }

    fun exportDiagnostics(uri: Uri, settingsRepository: SettingsRepository) = launchAction {
        val diagnostics = settingsRepository.diagnostics()
        withContext(Dispatchers.IO) {
            requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use {
                it.write(diagnostics)
            }
        }
        "脱敏诊断信息已导出"
    }

    fun logout() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                maintenanceService.logoutAndClearCredentials()
                logoutCompleted.value = true
            } catch (_: Exception) {
                message.value = "无法完整清除本地凭据"
            } finally {
                busy.value = false
            }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun launchAction(action: suspend () -> String) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            message.value = try {
                action()
            } catch (_: Exception) {
                "操作失败，请检查网络或当前数据状态"
            } finally {
                busy.value = false
            }
        }
    }
}

class SettingsViewModelFactory(
    private val settingsRepository: SettingsRepository,
    private val preferences: AppPreferences,
    private val secureConfigStore: SecureConfigStore,
    private val connectionService: SetupConnectionService,
    private val indexUpdater: () -> IndexAtomicUpdater?,
    private val maintenanceService: AppMaintenanceService,
    private val contentResolver: ContentResolver,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
        settingsRepository,
        preferences,
        secureConfigStore,
        connectionService,
        indexUpdater,
        maintenanceService,
        contentResolver,
    ) as T
}
