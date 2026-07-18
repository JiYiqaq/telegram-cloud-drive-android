package com.teledrive.lite.deletion

import kotlinx.coroutines.CancellationException

data class DeletionBatchResult(
    val completed: Int,
    val failed: Int,
    val retryableFileIds: List<String>,
    val retryDelaySeconds: Long?,
) {
    init {
        require(completed >= 0 && failed >= 0)
        require(retryableFileIds.size <= failed)
        require(retryableFileIds.none(String::isBlank))
        require((retryableFileIds.isEmpty()) == (retryDelaySeconds == null))
        require(retryDelaySeconds == null || retryDelaySeconds > 0)
    }
}

class DeletionBatchProcessor(
    private val execute: suspend (String) -> DeletionOutcome,
    private val recover: suspend (String, Exception) -> Unit,
    private val retryDelaySeconds: (Exception) -> Long? = { null },
) {
    suspend fun run(fileIds: List<String>): DeletionBatchResult {
        var completed = 0
        var failed = 0
        val retryableFileIds = mutableListOf<String>()
        var maximumRetryDelaySeconds: Long? = null
        fileIds.filter(String::isNotBlank).distinct().forEach { fileId ->
            try {
                when (execute(fileId)) {
                    DeletionOutcome.Completed -> completed += 1
                    is DeletionOutcome.PartiallyDeleted -> failed += 1
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed += 1
                retryDelaySeconds(error)?.coerceAtLeast(1)?.let { delay ->
                    retryableFileIds += fileId
                    maximumRetryDelaySeconds = maximumRetryDelaySeconds
                        ?.coerceAtLeast(delay)
                        ?: delay
                }
                try {
                    recover(fileId, error)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (recoveryError: Exception) {
                    error.addSuppressed(recoveryError)
                }
            }
        }
        return DeletionBatchResult(
            completed = completed,
            failed = failed,
            retryableFileIds = retryableFileIds,
            retryDelaySeconds = maximumRetryDelaySeconds,
        )
    }
}
