package com.teledrive.lite.repository

import com.teledrive.lite.telegram.TelegramApiException
import com.teledrive.lite.telegram.TelegramFailure
import com.teledrive.lite.telegram.TelegramGateway
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

fun interface TelegramGatewayFactory {
    fun create(token: String): TelegramGateway
}

data class BotConnectionResult(
    val displayName: String,
    val username: String?,
)

data class ChannelConnectionResult(
    val channelId: Long,
    val title: String?,
) {
    override fun toString(): String =
        "ChannelConnectionResult(channelId=[REDACTED], title=$title)"
}

data class DetectedChannel(
    val channelId: Long,
    val title: String?,
) {
    override fun toString(): String =
        "DetectedChannel(channelId=[REDACTED], title=$title)"
}

enum class ConnectionFailure(val chineseMessage: String) {
    INVALID_BOT_TOKEN("机器人 Token 无效，请检查后重试。"),
    NETWORK_UNAVAILABLE("无法连接 Telegram，请检查网络后重试。"),
    TELEGRAM_REQUEST_FAILED("Telegram 请求失败，请稍后重试。"),
    NOT_A_CHANNEL("该 Chat ID 不是频道，请使用私人频道的 Chat ID。"),
    BOT_NOT_ADMINISTRATOR("机器人不是频道管理员。"),
    MISSING_POST_PERMISSION("机器人缺少发送消息权限。"),
    MISSING_EDIT_PERMISSION("机器人缺少置顶或编辑消息权限。"),
    MISSING_DELETE_PERMISSION("机器人缺少删除消息权限。"),
    TEST_MESSAGE_CLEANUP_FAILED("连接测试消息无法自动删除，请手动删除后重试。"),
}

class ConnectionException(
    val failure: ConnectionFailure,
) : IllegalStateException(failure.chineseMessage)

class ConnectionRepository(
    private val clientFactory: TelegramGatewayFactory,
) {
    suspend fun testBot(token: String): BotConnectionResult = safely {
        val bot = clientFactory.create(token).getMe()
        BotConnectionResult(
            displayName = bot.displayName,
            username = bot.username,
        )
    }

    suspend fun testChannel(token: String, channelId: Long): ChannelConnectionResult = safely {
        val gateway = clientFactory.create(token)
        val bot = gateway.getMe()
        val chat = gateway.getChat(channelId)
        if (chat.type != CHANNEL_TYPE) {
            throw ConnectionException(ConnectionFailure.NOT_A_CHANNEL)
        }
        validatePermissions(gateway.getChatMember(channelId, bot.id))

        val testMessage = gateway.sendMessage(channelId, TEST_MESSAGE)
        val deleted = withContext(NonCancellable) {
            try {
                gateway.deleteMessage(channelId, testMessage.messageId)
            } catch (_: Exception) {
                false
            }
        }
        if (!deleted) {
            throw ConnectionException(ConnectionFailure.TEST_MESSAGE_CLEANUP_FAILED)
        }
        ChannelConnectionResult(channelId = chat.id, title = chat.title)
    }

    suspend fun detectChannels(
        token: String,
        offset: Long? = null,
    ): List<DetectedChannel> = safely {
        clientFactory.create(token)
            .getUpdates(offset)
            .distinctBy { it.chatId }
            .map { update ->
                DetectedChannel(
                    channelId = update.chatId,
                    title = update.chatTitle,
                )
            }
    }

    private fun validatePermissions(member: com.teledrive.lite.telegram.ChatMemberInfo) {
        when {
            !member.isAdministrator -> {
                throw ConnectionException(ConnectionFailure.BOT_NOT_ADMINISTRATOR)
            }
            !member.canPostMessages -> {
                throw ConnectionException(ConnectionFailure.MISSING_POST_PERMISSION)
            }
            !member.canEditMessages -> {
                throw ConnectionException(ConnectionFailure.MISSING_EDIT_PERMISSION)
            }
            !member.canDeleteMessages -> {
                throw ConnectionException(ConnectionFailure.MISSING_DELETE_PERMISSION)
            }
        }
    }

    private suspend fun <T> safely(block: suspend () -> T): T = try {
        block()
    } catch (error: ConnectionException) {
        throw error
    } catch (error: TelegramApiException) {
        throw ConnectionException(error.failure.toConnectionFailure())
    }

    private fun TelegramFailure.toConnectionFailure(): ConnectionFailure = when (this) {
        is TelegramFailure.Api -> if (errorCode == 401) {
            ConnectionFailure.INVALID_BOT_TOKEN
        } else {
            ConnectionFailure.TELEGRAM_REQUEST_FAILED
        }
        is TelegramFailure.Network -> ConnectionFailure.NETWORK_UNAVAILABLE
        else -> ConnectionFailure.TELEGRAM_REQUEST_FAILED
    }

    private companion object {
        const val CHANNEL_TYPE = "channel"
        const val TEST_MESSAGE = "TeleDrive Lite 连接测试，成功后会自动删除。"
    }
}
