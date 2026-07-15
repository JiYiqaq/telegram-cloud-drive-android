package com.teledrive.lite.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TelegramResponseDto<T>(
    val ok: Boolean,
    val result: T? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val description: String? = null,
    val parameters: TelegramResponseParametersDto? = null,
)

@Serializable
internal data class TelegramResponseParametersDto(
    @SerialName("retry_after") val retryAfter: Long? = null,
    @SerialName("migrate_to_chat_id") val migrateToChatId: Long? = null,
)

@Serializable
internal data class TelegramUserDto(
    val id: Long,
    @SerialName("is_bot") val isBot: Boolean,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
)

@Serializable
internal data class TelegramBasicChatDto(
    val id: Long,
    val type: String,
    val title: String? = null,
)

@Serializable
internal data class TelegramChatFullDto(
    val id: Long,
    val type: String,
    val title: String? = null,
    @SerialName("pinned_message") val pinnedMessage: TelegramMessageDto? = null,
)

@Serializable
internal data class TelegramMessageDto(
    @SerialName("message_id") val messageId: Long,
    val date: Long? = null,
    val chat: TelegramBasicChatDto,
    val document: TelegramDocumentDto? = null,
    val text: String? = null,
)

@Serializable
internal data class TelegramDocumentDto(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
)

@Serializable
internal data class TelegramChatMemberDto(
    val status: String,
    val user: TelegramUserDto,
    @SerialName("can_post_messages") val canPostMessages: Boolean = false,
    @SerialName("can_edit_messages") val canEditMessages: Boolean = false,
    @SerialName("can_delete_messages") val canDeleteMessages: Boolean = false,
)

@Serializable
internal data class TelegramUpdateDto(
    @SerialName("update_id") val updateId: Long,
    @SerialName("channel_post") val channelPost: TelegramMessageDto? = null,
    @SerialName("edited_channel_post") val editedChannelPost: TelegramMessageDto? = null,
)

@Serializable
internal data class TelegramFileDto(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("file_path") val filePath: String? = null,
)
