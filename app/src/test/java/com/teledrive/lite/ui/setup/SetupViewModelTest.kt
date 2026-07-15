package com.teledrive.lite.ui.setup

import com.teledrive.lite.repository.BotConnectionResult
import com.teledrive.lite.repository.ChannelConnectionResult
import com.teledrive.lite.repository.ConnectionException
import com.teledrive.lite.repository.ConnectionFailure
import com.teledrive.lite.repository.DetectedChannel
import com.teledrive.lite.repository.SetupConnectionService
import com.teledrive.lite.settings.ConfigValidationError
import com.teledrive.lite.settings.PasswordStrength
import com.teledrive.lite.settings.SetupInitializationService
import com.teledrive.lite.settings.ValidatedConnectionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupViewModelTest {
    private val connection = FakeConnectionService()
    private val initializer = FakeInitializer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val viewModel = SetupViewModel(connection, initializer, scope)

    @Test
    fun inputAndPasswordVisibilityUpdateStateAndStrength() {
        viewModel.onBotTokenChanged(VALID_TOKEN)
        viewModel.onChannelIdChanged(CHANNEL_ID)
        viewModel.onPasswordChanged("Strong password 123!")
        viewModel.onPasswordConfirmationChanged("Strong password 123!")
        viewModel.togglePasswordVisibility()

        val state = viewModel.uiState.value
        assertEquals(VALID_TOKEN, state.botToken)
        assertEquals(CHANNEL_ID, state.channelId)
        assertEquals(PasswordStrength.STRONG, state.passwordStrength)
        assertTrue(state.passwordVisible)
    }

    @Test
    fun botTestShowsIdentityWithoutEchoingToken() {
        connection.botResult = BotConnectionResult("Drive Bot", "drive_bot")
        viewModel.onBotTokenChanged(VALID_TOKEN)

        viewModel.testBot()

        val state = viewModel.uiState.value
        assertEquals("Drive Bot", state.botDisplayName)
        assertEquals("drive_bot", state.botUsername)
        assertEquals("机器人连接成功。", state.userMessage)
        assertFalse(state.userMessage.orEmpty().contains(VALID_TOKEN))
        assertEquals(SetupOperation.IDLE, state.operation)
    }

    @Test
    fun channelFailureUsesSafeSimplifiedChineseMessage() {
        connection.channelFailure = ConnectionException(ConnectionFailure.MISSING_EDIT_PERMISSION)
        enterValidFields()

        viewModel.testChannel()

        assertEquals("机器人缺少置顶或编辑消息权限。", viewModel.uiState.value.userMessage)
        assertFalse(viewModel.uiState.value.userMessage.orEmpty().contains(VALID_TOKEN))
    }

    @Test
    fun detectedChannelRequiresExplicitConfirmationBeforeFillingField() {
        connection.detected = listOf(
            DetectedChannel(channelId = -1001234567890, title = "私人云盘"),
            DetectedChannel(channelId = -1009999999999, title = "另一个频道"),
        )
        viewModel.onBotTokenChanged(VALID_TOKEN)

        viewModel.detectChannels()

        assertEquals("", viewModel.uiState.value.channelId)
        assertEquals(2, viewModel.uiState.value.detectedChannels.size)

        viewModel.confirmDetectedChannel(-1001234567890)

        assertEquals("-1001234567890", viewModel.uiState.value.channelId)
        assertTrue(viewModel.uiState.value.detectedChannels.isEmpty())
    }

    @Test
    fun invalidSaveShowsValidationErrorsWithoutNetworkOrPersistence() {
        viewModel.onBotTokenChanged("bad")
        viewModel.onChannelIdChanged("123")
        viewModel.onPasswordChanged("short")
        viewModel.onPasswordConfirmationChanged("different")

        viewModel.saveAndInitialize()

        val errors = viewModel.uiState.value.validationErrors
        assertTrue(ConfigValidationError.INVALID_BOT_TOKEN in errors)
        assertTrue(ConfigValidationError.INVALID_CHANNEL_ID in errors)
        assertTrue(ConfigValidationError.PASSWORD_TOO_SHORT in errors)
        assertTrue(ConfigValidationError.PASSWORD_MISMATCH in errors)
        assertEquals(0, connection.botCalls)
        assertEquals(0, initializer.calls)
    }

    @Test
    fun successfulSaveRechecksConnectionClearsSensitiveStateAndCompletes() {
        enterValidFields()

        viewModel.saveAndInitialize()

        val state = viewModel.uiState.value
        assertTrue(state.completed)
        assertEquals("", state.botToken)
        assertEquals("", state.password)
        assertEquals("", state.passwordConfirmation)
        assertEquals(1, connection.botCalls)
        assertEquals(1, connection.channelCalls)
        assertEquals(1, initializer.calls)
        assertTrue(checkNotNull(initializer.passwordReference).all { it == '\u0000' })
        assertEquals(VALID_TOKEN, initializer.config?.botToken)
    }

    private fun enterValidFields() {
        viewModel.onBotTokenChanged(VALID_TOKEN)
        viewModel.onChannelIdChanged(CHANNEL_ID)
        viewModel.onPasswordChanged("Strong password 123!")
        viewModel.onPasswordConfirmationChanged("Strong password 123!")
    }

    private class FakeConnectionService : SetupConnectionService {
        var botResult = BotConnectionResult("Drive Bot", "drive_bot")
        var channelFailure: ConnectionException? = null
        var detected: List<DetectedChannel> = emptyList()
        var botCalls = 0
        var channelCalls = 0

        override suspend fun testBot(token: String): BotConnectionResult {
            botCalls += 1
            return botResult
        }

        override suspend fun testChannel(token: String, channelId: Long): ChannelConnectionResult {
            channelCalls += 1
            channelFailure?.let { throw it }
            return ChannelConnectionResult(channelId, "私人云盘")
        }

        override suspend fun detectChannels(token: String, offset: Long?): List<DetectedChannel> = detected
    }

    private class FakeInitializer : SetupInitializationService {
        var calls = 0
        var config: ValidatedConnectionConfig? = null
        var passwordReference: CharArray? = null

        override suspend fun initialize(config: ValidatedConnectionConfig, password: CharArray) {
            calls += 1
            this.config = config
            passwordReference = password
        }
    }

    private companion object {
        const val VALID_TOKEN = "123456:AAabcdefghijklmnopqrstuvwxyz_1234"
        const val CHANNEL_ID = "-1001234567890"
    }
}
