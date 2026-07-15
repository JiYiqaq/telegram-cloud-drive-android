package com.teledrive.lite.repository

import com.teledrive.lite.telegram.BotIdentity
import com.teledrive.lite.telegram.ChannelUpdate
import com.teledrive.lite.telegram.ChatInfo
import com.teledrive.lite.telegram.ChatMemberInfo
import com.teledrive.lite.telegram.SentMessage
import com.teledrive.lite.telegram.TelegramGateway
import com.teledrive.lite.telegram.TelegramApiException
import com.teledrive.lite.telegram.TelegramFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConnectionRepositoryTest {
    @Test
    fun botTestReturnsPublicIdentityWithoutEchoingToken() = runBlocking {
        val gateway = FakeTelegramGateway()
        val token = dummyToken()
        val repository = repository(gateway, token)

        val result = repository.testBot(token)

        assertEquals("TeleDrive", result.displayName)
        assertEquals("teledrive_test_bot", result.username)
        assertFalse(result.toString().contains(token))
        assertEquals(listOf("getMe"), gateway.calls)
    }

    @Test
    fun unauthorizedBotErrorMapsToSafeChineseFailure() {
        val token = dummyToken()
        val gateway = FakeTelegramGateway(
            getMeFailure = TelegramApiException(
                TelegramFailure.Api(
                    errorCode = 401,
                    description = "Unauthorized",
                    retryAfterSeconds = null,
                    migrateToChatId = null,
                    httpStatusCode = 401,
                ),
            ),
        )
        val repository = repository(gateway, token)

        val error = assertThrows(ConnectionException::class.java) {
            runBlocking { repository.testBot(token) }
        }

        assertEquals(ConnectionFailure.INVALID_BOT_TOKEN, error.failure)
        assertTrue(error.message.orEmpty().any { it.code in 0x4E00..0x9FFF })
        assertFalse(error.message.orEmpty().contains(token))
    }

    @Test
    fun channelTestChecksIdentityChatPermissionsAndTemporaryMessageCleanup() = runBlocking {
        val gateway = FakeTelegramGateway()
        val repository = repository(gateway)

        val result = repository.testChannel(dummyToken(), CHANNEL_ID)

        assertEquals("Private Store", result.title)
        assertEquals(CHANNEL_ID, result.channelId)
        assertFalse(result.toString().contains(CHANNEL_ID.toString()))
        assertEquals(
            listOf(
                "getMe",
                "getChat:$CHANNEL_ID",
                "getChatMember:$CHANNEL_ID:42",
                "sendMessage:$CHANNEL_ID",
                "deleteMessage:$CHANNEL_ID:51",
            ),
            gateway.calls,
        )
    }

    @Test
    fun missingPermissionFailsBeforePostingTestMessage() = runBlocking {
        val gateway = FakeTelegramGateway(
            member = administrator(canDeleteMessages = false),
        )
        val repository = repository(gateway)

        val error = assertThrows(ConnectionException::class.java) {
            runBlocking { repository.testChannel(dummyToken(), CHANNEL_ID) }
        }

        assertEquals(ConnectionFailure.MISSING_DELETE_PERMISSION, error.failure)
        assertFalse(gateway.calls.any { it.startsWith("sendMessage") })
        assertTrue(error.message.orEmpty().any { it.code in 0x4E00..0x9FFF })
    }

    @Test
    fun failedTemporaryMessageDeletionIsReported() = runBlocking {
        val gateway = FakeTelegramGateway(deleteResult = false)
        val repository = repository(gateway)

        val error = assertThrows(ConnectionException::class.java) {
            runBlocking { repository.testChannel(dummyToken(), CHANNEL_ID) }
        }

        assertEquals(ConnectionFailure.TEST_MESSAGE_CLEANUP_FAILED, error.failure)
        assertTrue(gateway.calls.last().startsWith("deleteMessage"))
    }

    @Test
    fun nonTelegramCleanupExceptionUsesSafeCleanupFailure() = runBlocking {
        val gateway = FakeTelegramGateway(
            deleteFailure = IllegalStateException("local transport implementation failed"),
        )
        val repository = repository(gateway)

        val error = assertThrows(ConnectionException::class.java) {
            runBlocking { repository.testChannel(dummyToken(), CHANNEL_ID) }
        }

        assertEquals(ConnectionFailure.TEST_MESSAGE_CLEANUP_FAILED, error.failure)
        assertFalse(error.message.orEmpty().contains("transport implementation"))
    }

    @Test
    fun cancellationAfterSendStillCleansUpAndThenPropagatesCancellation() = runBlocking {
        val gateway = FakeTelegramGateway(
            cancelAfterSend = true,
            yieldBeforeDelete = true,
        )
        val repository = repository(gateway)

        supervisorScope {
            val result = async { repository.testChannel(dummyToken(), CHANNEL_ID) }
            try {
                result.await()
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                // Expected after non-cancellable cleanup completes.
            }
        }

        assertTrue(gateway.calls.contains("deleteMessage:$CHANNEL_ID:51"))
    }

    @Test
    fun channelDetectionReturnsDistinctCandidatesForExplicitConfirmation() = runBlocking {
        val gateway = FakeTelegramGateway(
            updates = listOf(
                ChannelUpdate(10L, 1L, CHANNEL_ID, "Private Store"),
                ChannelUpdate(11L, 2L, CHANNEL_ID, "Private Store"),
                ChannelUpdate(12L, 3L, -1007777777777L, "Archive"),
            ),
        )
        val repository = repository(gateway)

        val candidates = repository.detectChannels(dummyToken(), offset = 9L)

        assertEquals(2, candidates.size)
        assertEquals(CHANNEL_ID, candidates.first().channelId)
        assertFalse(candidates.first().toString().contains(CHANNEL_ID.toString()))
        assertEquals(listOf("getUpdates:9"), gateway.calls)
    }

    @Test
    fun nonChannelChatIsRejectedWithoutPosting() = runBlocking {
        val gateway = FakeTelegramGateway(
            chat = ChatInfo(CHANNEL_ID, "supergroup", "Wrong Chat", null),
        )
        val repository = repository(gateway)

        val error = assertThrows(ConnectionException::class.java) {
            runBlocking { repository.testChannel(dummyToken(), CHANNEL_ID) }
        }

        assertEquals(ConnectionFailure.NOT_A_CHANNEL, error.failure)
        assertFalse(gateway.calls.any { it.startsWith("sendMessage") })
    }

    private fun repository(
        gateway: TelegramGateway,
        expectedToken: String = dummyToken(),
    ): ConnectionRepository = ConnectionRepository(
        clientFactory = TelegramGatewayFactory { token ->
            assertEquals(expectedToken, token)
            gateway
        },
    )

    private class FakeTelegramGateway(
        private val chat: ChatInfo = ChatInfo(CHANNEL_ID, "channel", "Private Store", null),
        private val member: ChatMemberInfo = administrator(),
        private val deleteResult: Boolean = true,
        private val updates: List<ChannelUpdate> = emptyList(),
        private val getMeFailure: TelegramApiException? = null,
        private val deleteFailure: RuntimeException? = null,
        private val cancelAfterSend: Boolean = false,
        private val yieldBeforeDelete: Boolean = false,
    ) : TelegramGateway {
        val calls = mutableListOf<String>()

        override suspend fun getMe(): BotIdentity {
            calls += "getMe"
            getMeFailure?.let { throw it }
            return BotIdentity(42L, "TeleDrive", "teledrive_test_bot")
        }

        override suspend fun getChat(chatId: Long): ChatInfo {
            calls += "getChat:$chatId"
            return chat
        }

        override suspend fun getChatMember(chatId: Long, userId: Long): ChatMemberInfo {
            calls += "getChatMember:$chatId:$userId"
            return member
        }

        override suspend fun getUpdates(offset: Long?): List<ChannelUpdate> {
            calls += "getUpdates:${offset ?: "none"}"
            return updates
        }

        override suspend fun sendMessage(chatId: Long, text: String): SentMessage {
            calls += "sendMessage:$chatId"
            assertTrue(text.isNotBlank())
            if (cancelAfterSend) {
                currentCoroutineContext().cancel(CancellationException("cancel after send"))
            }
            return SentMessage(51L)
        }

        override suspend fun deleteMessage(chatId: Long, messageId: Long): Boolean {
            if (yieldBeforeDelete) yield()
            calls += "deleteMessage:$chatId:$messageId"
            deleteFailure?.let { throw it }
            return deleteResult
        }
    }

    private fun dummyToken(): String =
        "123456789:" + "AA_TEST_ONLY_abcdefghijklmnopqrstuvwxyz"

    private companion object {
        const val CHANNEL_ID = -1001234567890L

        fun administrator(
            canPostMessages: Boolean = true,
            canEditMessages: Boolean = true,
            canDeleteMessages: Boolean = true,
        ): ChatMemberInfo = ChatMemberInfo(
            status = "administrator",
            canPostMessages = canPostMessages,
            canEditMessages = canEditMessages,
            canDeleteMessages = canDeleteMessages,
        )
    }
}
