package com.teledrive.lite.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.lite.repository.ConnectionException
import com.teledrive.lite.repository.DetectedChannel
import com.teledrive.lite.repository.SetupConnectionService
import com.teledrive.lite.settings.ConfigValidationError
import com.teledrive.lite.settings.ConfigValidator
import com.teledrive.lite.settings.PasswordStrength
import com.teledrive.lite.settings.SecureStorageException
import com.teledrive.lite.settings.SetupInitializationService
import com.teledrive.lite.util.SecureErase
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupOperation {
    IDLE,
    TESTING_BOT,
    TESTING_CHANNEL,
    DETECTING_CHANNELS,
    SAVING,
}

data class SetupUiState(
    val botToken: String = "",
    val channelId: String = "",
    val password: String = "",
    val passwordConfirmation: String = "",
    val passwordVisible: Boolean = false,
    val passwordStrength: PasswordStrength = PasswordStrength.WEAK,
    val validationErrors: Set<ConfigValidationError> = emptySet(),
    val operation: SetupOperation = SetupOperation.IDLE,
    val botDisplayName: String? = null,
    val botUsername: String? = null,
    val channelTitle: String? = null,
    val detectedChannels: List<DetectedChannel> = emptyList(),
    val userMessage: String? = null,
    val completed: Boolean = false,
)

class SetupViewModel(
    private val connectionService: SetupConnectionService,
    private val initializationService: SetupInitializationService,
    private val providedScope: CoroutineScope? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = mutableUiState.asStateFlow()

    fun onBotTokenChanged(value: String) = edit {
        copy(botToken = value, botDisplayName = null, botUsername = null)
    }

    fun onChannelIdChanged(value: String) = edit { copy(channelId = value, channelTitle = null) }

    fun onPasswordChanged(value: String) = edit {
        copy(password = value, passwordStrength = ConfigValidator.passwordStrength(value))
    }

    fun onPasswordConfirmationChanged(value: String) = edit { copy(passwordConfirmation = value) }

    fun togglePasswordVisibility() = mutableUiState.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun testBot() {
        val token = mutableUiState.value.botToken.trim()
        if (!ConfigValidator.isBotTokenValid(token)) {
            validationFailure(setOf(ConfigValidationError.INVALID_BOT_TOKEN))
            return
        }
        launchOperation(SetupOperation.TESTING_BOT) {
            val bot = connectionService.testBot(token)
            mutableUiState.update {
                it.copy(
                    botDisplayName = bot.displayName,
                    botUsername = bot.username,
                    userMessage = "机器人连接成功。",
                )
            }
        }
    }

    fun testChannel() {
        val state = mutableUiState.value
        val errors = connectionFieldErrors(state.botToken, state.channelId)
        val parsedChannelId = ConfigValidator.parsePrivateChannelId(state.channelId)
        if (errors.isNotEmpty() || parsedChannelId == null) {
            validationFailure(errors)
            return
        }
        launchOperation(SetupOperation.TESTING_CHANNEL) {
            val channel = connectionService.testChannel(state.botToken.trim(), parsedChannelId)
            mutableUiState.update {
                it.copy(channelTitle = channel.title, userMessage = "频道连接和权限测试成功。")
            }
        }
    }

    fun detectChannels() {
        val token = mutableUiState.value.botToken.trim()
        if (!ConfigValidator.isBotTokenValid(token)) {
            validationFailure(setOf(ConfigValidationError.INVALID_BOT_TOKEN))
            return
        }
        launchOperation(SetupOperation.DETECTING_CHANNELS) {
            val channels = connectionService.detectChannels(token)
            mutableUiState.update {
                it.copy(
                    detectedChannels = channels,
                    userMessage = if (channels.isEmpty()) {
                        "未检测到频道，请按帮助中的手动方法填写 Chat ID。"
                    } else {
                        "请选择并确认正确的私人频道。"
                    },
                )
            }
        }
    }

    fun confirmDetectedChannel(channelId: Long) {
        val candidate = mutableUiState.value.detectedChannels.firstOrNull { it.channelId == channelId }
            ?: return
        mutableUiState.update {
            it.copy(
                channelId = candidate.channelId.toString(),
                channelTitle = candidate.title,
                detectedChannels = emptyList(),
                validationErrors = it.validationErrors - ConfigValidationError.INVALID_CHANNEL_ID,
                userMessage = "已填入所选频道，请继续测试频道权限。",
            )
        }
    }

    fun saveAndInitialize() {
        val state = mutableUiState.value
        val validation = ConfigValidator.validate(
            botToken = state.botToken,
            channelId = state.channelId,
            password = state.password,
            passwordConfirmation = state.passwordConfirmation,
        )
        val config = validation.config
        if (!validation.isValid || config == null) {
            mutableUiState.update {
                it.copy(
                    validationErrors = validation.errors,
                    passwordStrength = validation.passwordStrength,
                    userMessage = "请先修正标出的配置内容。",
                )
            }
            return
        }

        launchOperation(SetupOperation.SAVING) {
            connectionService.testBot(config.botToken)
            val channel = connectionService.testChannel(config.botToken, config.channelId)
            val password = state.password.toCharArray()
            try {
                initializationService.initialize(config, password)
            } finally {
                SecureErase.wipe(password)
            }
            mutableUiState.value = SetupUiState(
                channelTitle = channel.title,
                userMessage = "配置已安全保存并初始化。",
                completed = true,
            )
        }
    }

    private fun launchOperation(operation: SetupOperation, block: suspend () -> Unit) {
        if (mutableUiState.value.operation != SetupOperation.IDLE) return
        val scope = providedScope ?: viewModelScope
        scope.launch {
            mutableUiState.update { it.copy(operation = operation, userMessage = null) }
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ConnectionException) {
                mutableUiState.update { it.copy(userMessage = error.failure.chineseMessage) }
            } catch (_: SecureStorageException) {
                mutableUiState.update { it.copy(userMessage = "安全配置保存失败，请重试。") }
            } catch (_: Exception) {
                mutableUiState.update { it.copy(userMessage = "操作失败，请检查网络和配置后重试。") }
            } finally {
                mutableUiState.update { it.copy(operation = SetupOperation.IDLE) }
            }
        }
    }

    private fun connectionFieldErrors(
        token: String,
        channelId: String,
    ): Set<ConfigValidationError> = buildSet {
        if (!ConfigValidator.isBotTokenValid(token)) add(ConfigValidationError.INVALID_BOT_TOKEN)
        if (ConfigValidator.parsePrivateChannelId(channelId) == null) {
            add(ConfigValidationError.INVALID_CHANNEL_ID)
        }
    }

    private fun validationFailure(errors: Set<ConfigValidationError>) {
        mutableUiState.update {
            it.copy(validationErrors = errors, userMessage = "请先修正标出的配置内容。")
        }
    }

    private fun edit(transform: SetupUiState.() -> SetupUiState) {
        mutableUiState.update {
            it.transform().copy(validationErrors = emptySet(), userMessage = null, completed = false)
        }
    }
}
