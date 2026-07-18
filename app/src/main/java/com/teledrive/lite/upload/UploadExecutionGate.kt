package com.teledrive.lite.upload

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes uploads inside the app process without coupling their WorkManager outcomes.
 *
 * Every UploadWorker runs in the same application process, so a process-wide gate preserves the
 * original one-at-a-time behavior. Unlike a WorkManager prerequisite chain, a failed or canceled
 * upload releases this gate and cannot block the next independent work request.
 */
class UploadExecutionGate(
    private val mutex: Mutex = Mutex(),
) {
    suspend fun <T> withPermit(block: suspend () -> T): T = mutex.withLock { block() }

    companion object {
        val processWide = UploadExecutionGate()
    }
}

class UploadQueueMutationGate(
    private val mutex: Mutex = Mutex(),
) {
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

object UploadWorkIdentity {
    const val LEGACY_SERIAL_QUEUE = "teledrive_serial_upload_queue_v1"
    private const val ISOLATED_UPLOAD_PREFIX = "teledrive_upload_task_v2:"

    fun uniqueName(taskId: String): String {
        require(taskId.isNotBlank())
        return "$ISOLATED_UPLOAD_PREFIX$taskId"
    }
}
