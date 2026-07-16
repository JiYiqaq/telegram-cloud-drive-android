package com.teledrive.lite.download

import com.teledrive.lite.database.TransferTaskEntity
import com.teledrive.lite.model.TransferStatus

object DownloadRetryPolicy {
    const val AUTHENTICATION_ERROR = "DOWNLOAD_AUTHENTICATION_FAILED"
    const val INTEGRITY_ERROR = "DOWNLOAD_INTEGRITY_FAILED"
    const val REMOTE_SIZE_ERROR = "DOWNLOAD_REMOTE_SIZE_MISMATCH"
    const val DESTINATION_ERROR = "DOWNLOAD_DESTINATION_UNAVAILABLE"
    const val INVALID_STATE_ERROR = "DOWNLOAD_INVALID_STATE"

    fun canCancel(task: TransferTaskEntity): Boolean = task.status !in setOf(
        TransferStatus.SUCCESS,
        TransferStatus.FAILED,
        TransferStatus.CANCELED,
    )

    fun canRetry(task: TransferTaskEntity): Boolean = when (task.status) {
        TransferStatus.CANCELED -> true
        TransferStatus.FAILED -> task.errorCode !in NON_RETRYABLE_ERRORS
        else -> false
    }

    private val NON_RETRYABLE_ERRORS = setOf(
        AUTHENTICATION_ERROR,
        INTEGRITY_ERROR,
        REMOTE_SIZE_ERROR,
        DESTINATION_ERROR,
        INVALID_STATE_ERROR,
    )
}
