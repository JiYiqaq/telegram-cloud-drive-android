package com.teledrive.lite.telegram

import java.io.InputStream
import java.io.OutputStream

interface TelegramGateway {
    suspend fun getMe(): BotIdentity

    suspend fun getChat(chatId: Long): ChatInfo

    suspend fun getChatMember(chatId: Long, userId: Long): ChatMemberInfo

    suspend fun getUpdates(offset: Long? = null): List<ChannelUpdate>

    suspend fun sendMessage(chatId: Long, text: String): SentMessage

    suspend fun deleteMessage(chatId: Long, messageId: Long): Boolean
}

interface TelegramStorageGateway : TelegramGateway {
    suspend fun sendDocument(
        chatId: Long,
        fileName: String,
        contentLength: Long,
        openStream: () -> InputStream,
    ): SentDocument

    suspend fun editDocument(
        chatId: Long,
        messageId: Long,
        fileName: String,
        contentLength: Long,
        openStream: () -> InputStream,
    ): SentDocument

    suspend fun pinChatMessage(chatId: Long, messageId: Long): Boolean

    suspend fun unpinChatMessage(chatId: Long, messageId: Long): Boolean

    suspend fun getFile(fileId: String): RemoteFile

    suspend fun downloadFile(filePath: String, output: OutputStream): Long
}
