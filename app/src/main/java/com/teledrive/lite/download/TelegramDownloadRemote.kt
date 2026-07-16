package com.teledrive.lite.download

import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.telegram.TelegramCloudLimits
import com.teledrive.lite.telegram.TelegramStorageGateway
import com.teledrive.lite.util.BoundedByteArrayOutputStream
import com.teledrive.lite.util.BoundedOutputExceededException

class TelegramDownloadRemote(
    private val gateway: TelegramStorageGateway,
) : DownloadRemote {
    override suspend fun downloadChunk(
        telegramFileId: String,
        expectedEncryptedSizeBytes: Long,
    ): ByteArray {
        if (
            telegramFileId.isBlank() ||
            expectedEncryptedSizeBytes < CryptoEngine.ENVELOPE_OVERHEAD_BYTES ||
            !TelegramCloudLimits.isEncryptedChunkSizeSafe(expectedEncryptedSizeBytes)
        ) {
            fail(DownloadFailure.REMOTE_SIZE_MISMATCH)
        }

        val remoteFile = gateway.getFile(telegramFileId)
        if (
            remoteFile.fileId != telegramFileId ||
            remoteFile.filePath.isBlank() ||
            remoteFile.size != expectedEncryptedSizeBytes
        ) {
            fail(DownloadFailure.REMOTE_SIZE_MISMATCH)
        }

        val output = BoundedByteArrayOutputStream(expectedEncryptedSizeBytes.toInt())
        val reportedBytes = try {
            gateway.downloadFile(remoteFile.filePath, output)
        } catch (error: BoundedOutputExceededException) {
            throw DownloadException(DownloadFailure.REMOTE_SIZE_MISMATCH, error)
        }
        if (
            reportedBytes != expectedEncryptedSizeBytes ||
            output.size().toLong() != expectedEncryptedSizeBytes
        ) {
            fail(DownloadFailure.REMOTE_SIZE_MISMATCH)
        }
        return output.toByteArray()
    }

    private fun fail(failure: DownloadFailure): Nothing = throw DownloadException(failure)
}
