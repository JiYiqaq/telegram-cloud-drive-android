package com.teledrive.lite.deletion

import com.teledrive.lite.telegram.BotIdentity
import com.teledrive.lite.telegram.ChannelUpdate
import com.teledrive.lite.telegram.ChatInfo
import com.teledrive.lite.telegram.ChatMemberInfo
import com.teledrive.lite.telegram.RemoteFile
import com.teledrive.lite.telegram.SentDocument
import com.teledrive.lite.telegram.SentMessage
import com.teledrive.lite.telegram.TelegramApiException
import com.teledrive.lite.telegram.TelegramFailure
import com.teledrive.lite.telegram.TelegramStorageGateway
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramDeletionRemoteTest {
    @Test
    fun alreadyMissingMessageIsAnIdempotentDeletionSuccess() = runBlocking {
        val gateway = FakeGateway(
            TelegramApiException(
                TelegramFailure.Api(
                    errorCode = 400,
                    description = "Bad Request: message to delete not found",
                    retryAfterSeconds = null,
                    migrateToChatId = null,
                    httpStatusCode = 400,
                ),
            ),
        )

        assertTrue(TelegramDeletionRemote(gateway, -1001).deleteMessage(51))
    }

    @Test
    fun permissionFailureIsNotMistakenForAnAlreadyDeletedMessage() {
        val gateway = FakeGateway(
            TelegramApiException(
                TelegramFailure.Api(400, "Bad Request: message can't be deleted", null, null, 400),
            ),
        )

        assertThrows(TelegramApiException::class.java) {
            runBlocking { TelegramDeletionRemote(gateway, -1001).deleteMessage(51) }
        }
    }

    private class FakeGateway(private val deletionFailure: Exception) : TelegramStorageGateway {
        override suspend fun deleteMessage(chatId: Long, messageId: Long): Boolean =
            throw deletionFailure

        override suspend fun getMe() = BotIdentity(1, "bot", "bot")
        override suspend fun getChat(chatId: Long) = ChatInfo(chatId, "channel", "test", null)
        override suspend fun getChatMember(chatId: Long, userId: Long) =
            ChatMemberInfo("administrator", true, true, true)
        override suspend fun getUpdates(offset: Long?): List<ChannelUpdate> = emptyList()
        override suspend fun sendMessage(chatId: Long, text: String) = SentMessage(1)
        override suspend fun sendDocument(
            chatId: Long,
            fileName: String,
            contentLength: Long,
            openStream: () -> InputStream,
        ) = SentDocument(1, "file", null, contentLength)
        override suspend fun editDocument(
            chatId: Long,
            messageId: Long,
            fileName: String,
            contentLength: Long,
            openStream: () -> InputStream,
        ) = SentDocument(messageId, "file", null, contentLength)
        override suspend fun pinChatMessage(chatId: Long, messageId: Long) = true
        override suspend fun unpinChatMessage(chatId: Long, messageId: Long) = true
        override suspend fun getFile(fileId: String) = RemoteFile(fileId, null, 0, "file")
        override suspend fun downloadFile(filePath: String, output: OutputStream) = 0L
    }
}
