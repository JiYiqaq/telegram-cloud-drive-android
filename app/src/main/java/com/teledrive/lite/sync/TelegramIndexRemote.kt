package com.teledrive.lite.sync

import com.teledrive.lite.crypto.CloudIndexEnvelopeCryptor
import com.teledrive.lite.telegram.TelegramCloudLimits
import com.teledrive.lite.telegram.TelegramStorageGateway
import com.teledrive.lite.util.BoundedByteArrayOutputStream
import java.io.ByteArrayInputStream

interface IndexRecoveryRemote {
    suspend fun getPinned(): RemoteIndexDocument?
    suspend fun download(document: RemoteIndexDocument): ByteArray
}

enum class IndexRemoteFailure {
    INVALID_DOCUMENT,
    INVALID_PINNED_MESSAGE,
    SIZE_LIMIT_EXCEEDED,
    SIZE_MISMATCH,
    REMOTE_REJECTED,
}

class IndexRemoteException(
    val failure: IndexRemoteFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.name, cause)

class TelegramIndexRemote(
    private val gateway: TelegramStorageGateway,
    private val chatId: Long,
) : IndexRemote, IndexRecoveryRemote {
    init {
        require(chatId < 0) { "Telegram channel ID must be negative" }
    }

    override suspend fun sendProvisional(operationId: String): ProvisionalIndexMessage {
        require(operationId.isNotBlank())
        val bytes = PROVISIONAL_CONTENT
        val sent = gateway.sendDocument(
            chatId = chatId,
            fileName = IndexAtomicUpdater.INDEX_FILE_NAME,
            contentLength = bytes.size.toLong(),
            openStream = { ByteArrayInputStream(bytes) },
        )
        return ProvisionalIndexMessage(sent.messageId)
    }

    override suspend fun editToFinal(
        messageId: Long,
        candidate: IndexCandidate,
    ): RemoteIndexDocument {
        val content = candidate.content
        val sent = try {
            gateway.editDocument(
                chatId = chatId,
                messageId = messageId,
                fileName = candidate.fileName,
                contentLength = candidate.sizeBytes,
                openStream = { ByteArrayInputStream(content) },
            )
        } finally {
            content.fill(0)
        }
        if (
            sent.messageId != messageId ||
            sent.fileId.isBlank() ||
            sent.size != candidate.sizeBytes
        ) {
            fail(IndexRemoteFailure.INVALID_DOCUMENT)
        }
        return RemoteIndexDocument(
            messageId = sent.messageId,
            fileId = sent.fileId,
            fileName = candidate.fileName,
            sizeBytes = checkNotNull(sent.size),
        )
    }

    override suspend fun pin(messageId: Long) {
        if (!gateway.pinChatMessage(chatId, messageId)) fail(IndexRemoteFailure.REMOTE_REJECTED)
    }

    override suspend fun getPinned(): RemoteIndexDocument? {
        val message = gateway.getChat(chatId).pinnedMessage ?: return null
        val document = message.document ?: fail(IndexRemoteFailure.INVALID_PINNED_MESSAGE)
        val fileName = document.fileName ?: fail(IndexRemoteFailure.INVALID_PINNED_MESSAGE)
        val mimeType = document.mimeType ?: fail(IndexRemoteFailure.INVALID_PINNED_MESSAGE)
        val size = document.size ?: fail(IndexRemoteFailure.INVALID_PINNED_MESSAGE)
        if (
            message.messageId <= 0 ||
            document.fileId.isBlank() ||
            fileName != IndexAtomicUpdater.INDEX_FILE_NAME ||
            mimeType != RemoteIndexDocument.INDEX_MIME_TYPE ||
            size <= 0 ||
            size > CloudIndexEnvelopeCryptor.MAX_ENVELOPE_BYTES
        ) {
            fail(IndexRemoteFailure.INVALID_PINNED_MESSAGE)
        }
        return RemoteIndexDocument(
            messageId = message.messageId,
            fileId = document.fileId,
            fileName = fileName,
            sizeBytes = size,
            mimeType = mimeType,
        )
    }

    override suspend fun unpin(messageId: Long) {
        if (!gateway.unpinChatMessage(chatId, messageId)) fail(IndexRemoteFailure.REMOTE_REJECTED)
    }

    override suspend fun delete(messageId: Long) {
        if (!gateway.deleteMessage(chatId, messageId)) fail(IndexRemoteFailure.REMOTE_REJECTED)
    }

    override suspend fun download(document: RemoteIndexDocument): ByteArray {
        if (
            document.fileName != IndexAtomicUpdater.INDEX_FILE_NAME ||
            document.mimeType != RemoteIndexDocument.INDEX_MIME_TYPE ||
            document.sizeBytes <= 0 ||
            document.sizeBytes > CloudIndexEnvelopeCryptor.MAX_ENVELOPE_BYTES ||
            !TelegramCloudLimits.isEncryptedChunkSizeSafe(document.sizeBytes)
        ) {
            fail(IndexRemoteFailure.SIZE_LIMIT_EXCEEDED)
        }
        val remoteFile = gateway.getFile(document.fileId)
        if (
            remoteFile.fileId != document.fileId ||
            remoteFile.filePath.isBlank() ||
            remoteFile.size != document.sizeBytes
        ) {
            fail(IndexRemoteFailure.SIZE_MISMATCH)
        }

        val output = BoundedByteArrayOutputStream(document.sizeBytes.toInt())
        val reportedBytes = try {
            gateway.downloadFile(remoteFile.filePath, output)
        } catch (error: Exception) {
            throw IndexRemoteException(IndexRemoteFailure.SIZE_MISMATCH, error)
        }
        if (reportedBytes != document.sizeBytes || output.size().toLong() != document.sizeBytes) {
            fail(IndexRemoteFailure.SIZE_MISMATCH)
        }
        return output.toByteArray()
    }

    private fun fail(failure: IndexRemoteFailure): Nothing = throw IndexRemoteException(failure)

    private companion object {
        val PROVISIONAL_CONTENT = "teledrive-index-provisional-v1".encodeToByteArray()
    }
}
