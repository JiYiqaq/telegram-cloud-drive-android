package com.teledrive.lite.repository

import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.PendingOperationStatus

data class DeletionFailureTransition(
    val fileStatus: FileStatus,
    val operationStatus: PendingOperationStatus,
    val attempt: Int,
    val errorCode: String,
)

data class DeletionRetryTransition(
    val fileStatus: FileStatus,
    val operationStatus: PendingOperationStatus,
    val errorCode: String?,
)

object DeletionFailureRecovery {
    fun transition(
        currentFileStatus: FileStatus,
        currentAttempt: Int,
        errorCode: String,
    ): DeletionFailureTransition {
        require(currentFileStatus in RECOVERABLE_DELETION_STATES)
        require(currentAttempt >= 0)
        require(errorCode.isNotBlank())
        return DeletionFailureTransition(
            fileStatus = FileStatus.PARTIALLY_DELETED,
            operationStatus = PendingOperationStatus.FAILED,
            attempt = currentAttempt + 1,
            errorCode = errorCode,
        )
    }

    fun retryTransition(currentFileStatus: FileStatus): DeletionRetryTransition {
        require(currentFileStatus == FileStatus.PARTIALLY_DELETED)
        return DeletionRetryTransition(
            fileStatus = FileStatus.DELETING,
            operationStatus = PendingOperationStatus.PENDING,
            errorCode = null,
        )
    }

    private val RECOVERABLE_DELETION_STATES = setOf(
        FileStatus.DELETING,
        FileStatus.PARTIALLY_DELETED,
    )
}
