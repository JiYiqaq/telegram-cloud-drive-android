package com.teledrive.lite.deletion

import java.util.concurrent.CancellationException

data class PendingChunkDeletion(
    val chunkId: String,
    val messageId: Long,
) {
    init {
        require(chunkId.isNotBlank() && messageId > 0)
    }
}

interface SafeDeletionStore {
    suspend fun pendingChunks(fileId: String): List<PendingChunkDeletion>

    suspend fun recordResult(
        fileId: String,
        chunkId: String,
        deleted: Boolean,
        errorCode: String? = null,
    )
}

fun interface DeletionRemote {
    suspend fun deleteMessage(messageId: Long): Boolean
}

interface DeletionIndexPublisher {
    suspend fun publishPartial(fileId: String)
    suspend fun publishRemoval(fileId: String)
}

sealed interface DeletionOutcome {
    data object Completed : DeletionOutcome
    data class PartiallyDeleted(val failedChunks: Int) : DeletionOutcome
}

class SafeDeletionCoordinator(
    private val store: SafeDeletionStore,
    private val remote: DeletionRemote,
    private val indexPublisher: DeletionIndexPublisher,
    private val errorCode: (Throwable) -> String = { "REMOTE_DELETE_FAILED" },
) {
    suspend fun execute(fileId: String): DeletionOutcome {
        require(fileId.isNotBlank())
        var failedChunks = 0
        store.pendingChunks(fileId).forEach { chunk ->
            val deleted = try {
                remote.deleteMessage(chunk.messageId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failedChunks += 1
                store.recordResult(
                    fileId,
                    chunk.chunkId,
                    deleted = false,
                    errorCode = errorCode(error),
                )
                return@forEach
            }
            if (deleted) {
                store.recordResult(fileId, chunk.chunkId, deleted = true)
            } else {
                failedChunks += 1
                store.recordResult(
                    fileId,
                    chunk.chunkId,
                    deleted = false,
                    errorCode = REMOTE_REJECTED,
                )
            }
        }
        return if (failedChunks == 0) {
            indexPublisher.publishRemoval(fileId)
            DeletionOutcome.Completed
        } else {
            indexPublisher.publishPartial(fileId)
            DeletionOutcome.PartiallyDeleted(failedChunks)
        }
    }

    private companion object {
        const val REMOTE_REJECTED = "REMOTE_REJECTED"
    }
}
