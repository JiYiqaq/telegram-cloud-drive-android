package com.teledrive.lite.upload

import com.teledrive.lite.telegram.TelegramStorageGateway
import java.io.ByteArrayInputStream

class TelegramUploadRemote(
    private val gateway: TelegramStorageGateway,
    private val chatId: Long,
) : UploadRemote {
    override suspend fun sendChunk(
        fileName: String,
        encryptedBytes: ByteArray,
        partIndex: Int,
    ): UploadedRemoteChunk {
        require(partIndex >= 0)
        val sent = gateway.sendDocument(
            chatId = chatId,
            fileName = fileName,
            contentLength = encryptedBytes.size.toLong(),
            openStream = { ByteArrayInputStream(encryptedBytes) },
        )
        return UploadedRemoteChunk(
            messageId = sent.messageId,
            telegramFileId = sent.fileId,
            // Telegram's Document.file_size is optional. The locally streamed body length is exact.
            encryptedSizeBytes = encryptedBytes.size.toLong(),
        )
    }
}
