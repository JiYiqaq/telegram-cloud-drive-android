package com.teledrive.lite.telegram

interface TelegramGateway {
    suspend fun getMe(): BotIdentity

    suspend fun getChat(chatId: Long): ChatInfo

    suspend fun getChatMember(chatId: Long, userId: Long): ChatMemberInfo

    suspend fun getUpdates(offset: Long? = null): List<ChannelUpdate>

    suspend fun sendMessage(chatId: Long, text: String): SentMessage

    suspend fun deleteMessage(chatId: Long, messageId: Long): Boolean
}
