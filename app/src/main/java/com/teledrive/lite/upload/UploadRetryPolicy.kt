package com.teledrive.lite.upload

import com.teledrive.lite.database.TransferTaskEntity
import com.teledrive.lite.model.TransferStatus

object UploadRetryPolicy {
    const val RESULT_UNKNOWN_ERROR = "UPLOAD_RESULT_UNKNOWN"
    const val REMOTE_RESULT_UNKNOWN_ERROR = "UPLOAD_REMOTE_RESULT_UNKNOWN"
    const val SOURCE_CHANGED_ERROR = "UPLOAD_SOURCE_CHANGED"
    const val SOURCE_UNAVAILABLE_ERROR = "UPLOAD_SOURCE_UNAVAILABLE"

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
        RESULT_UNKNOWN_ERROR,
        REMOTE_RESULT_UNKNOWN_ERROR,
        SOURCE_CHANGED_ERROR,
        SOURCE_UNAVAILABLE_ERROR,
    )
}
