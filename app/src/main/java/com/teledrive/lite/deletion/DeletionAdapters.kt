package com.teledrive.lite.deletion

import com.teledrive.lite.repository.FileRepository
import com.teledrive.lite.repository.DriveRepositoryException
import com.teledrive.lite.repository.DriveRepositoryFailure
import com.teledrive.lite.sync.IndexAtomicUpdater
import com.teledrive.lite.sync.PinnedIndexSnapshotReader
import com.teledrive.lite.telegram.TelegramStorageGateway
import com.teledrive.lite.telegram.TelegramApiException
import com.teledrive.lite.telegram.TelegramFailure

class RepositoryDeletionStore(
    private val repository: FileRepository,
) : SafeDeletionStore {
    override suspend fun pendingChunks(fileId: String): List<PendingChunkDeletion> =
        repository.pendingChunkDeletions(fileId)

    override suspend fun recordResult(
        fileId: String,
        chunkId: String,
        deleted: Boolean,
        errorCode: String?,
    ) {
        repository.recordChunkDeletionResult(fileId, chunkId, deleted, errorCode)
    }
}

class TelegramDeletionRemote(
    private val gateway: TelegramStorageGateway,
    private val chatId: Long,
) : DeletionRemote {
    init {
        require(chatId < 0)
    }

    override suspend fun deleteMessage(messageId: Long): Boolean {
        require(messageId > 0)
        return try {
            gateway.deleteMessage(chatId, messageId)
        } catch (error: TelegramApiException) {
            val api = error.failure as? TelegramFailure.Api
            if (
                api?.errorCode == 400 &&
                api.description.contains(MESSAGE_NOT_FOUND, ignoreCase = true)
            ) {
                true
            } else {
                throw error
            }
        }
    }

    private companion object {
        const val MESSAGE_NOT_FOUND = "message to delete not found"
    }
}

class VerifiedDeletionIndexPublisher(
    private val updater: IndexAtomicUpdater,
    private val snapshotReader: PinnedIndexSnapshotReader,
    private val repository: FileRepository,
    private val maximumAttempts: Int = 3,
) : DeletionIndexPublisher {
    init {
        require(maximumAttempts > 0)
    }

    override suspend fun publishPartial(fileId: String) {
        repeat(maximumAttempts) {
            val outcome = updater.resumeOrStart()
            if (fileId in outcome.publishedFileIds) return
        }
        throw IllegalStateException("Partial deletion state was not published")
    }

    override suspend fun publishRemoval(fileId: String) {
        repeat(maximumAttempts) { attempt ->
            val outcome = updater.resumeOrStart()
            if (fileId !in outcome.publishedFileIds) {
                val confirmed = snapshotReader.readExpected(outcome.stableState)
                require(confirmed.files.none { file -> file.id == fileId })
                try {
                    repository.finalizeFileDeletion(fileId, confirmed)
                    return
                } catch (error: DriveRepositoryException) {
                    if (
                        error.failure != DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED ||
                        attempt == maximumAttempts - 1
                    ) {
                        throw error
                    }
                }
            }
        }
        throw IllegalStateException("Deleted file remained in the pinned index")
    }
}
