package com.teledrive.lite.telegram

data class BotIdentity(
    val id: Long,
    val displayName: String,
    val username: String?,
)

data class ChatInfo(
    val id: Long,
    val type: String,
    val title: String?,
    val pinnedMessage: TelegramMessage?,
)

data class TelegramMessage(
    val messageId: Long,
    val document: TelegramDocument?,
)

data class TelegramDocument(
    val fileId: String,
    val fileUniqueId: String?,
    val fileName: String?,
    val mimeType: String?,
    val size: Long?,
)

data class ChatMemberInfo(
    val status: String,
    val canPostMessages: Boolean,
    val canEditMessages: Boolean,
    val canDeleteMessages: Boolean,
) {
    val isAdministrator: Boolean
        get() = status == "administrator" || status == "creator"
}

data class ChannelUpdate(
    val updateId: Long,
    val messageId: Long,
    val chatId: Long,
    val chatTitle: String?,
)

data class SentMessage(
    val messageId: Long,
)

data class SentDocument(
    val messageId: Long,
    val fileId: String,
    val fileUniqueId: String?,
    val size: Long?,
)

data class RemoteFile(
    val fileId: String,
    val fileUniqueId: String?,
    val size: Long?,
    val filePath: String,
)

object TelegramCloudLimits {
    const val DEFAULT_CHUNK_SIZE_BYTES: Long = 18L * 1024L * 1024L
    const val MAX_DOWNLOADABLE_ENCRYPTED_BYTES: Long = 20_000_000L
    const val GCM_TAG_BYTES: Long = 16L

    fun isEncryptedChunkSizeSafe(size: Long): Boolean =
        size >= 0L && size < MAX_DOWNLOADABLE_ENCRYPTED_BYTES
}
