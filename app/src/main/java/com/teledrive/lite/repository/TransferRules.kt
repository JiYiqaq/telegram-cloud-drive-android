package com.teledrive.lite.repository

import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType

object TransferFileEligibility {
    fun canExecute(
        type: TransferType,
        fileStatus: FileStatus,
        isCloudIndexed: Boolean,
    ): Boolean = when (type) {
        TransferType.UPLOAD -> !isCloudIndexed && fileStatus in UPLOAD_STATES
        TransferType.DOWNLOAD -> isCloudIndexed && fileStatus in DOWNLOAD_STATES
    }

    private val UPLOAD_STATES = setOf(
        FileStatus.PENDING,
        FileStatus.ENCRYPTING,
        FileStatus.UPLOADING,
        FileStatus.FAILED,
    )
    private val DOWNLOAD_STATES = setOf(
        FileStatus.AVAILABLE,
        FileStatus.DOWNLOADING,
        FileStatus.CORRUPTED,
        FileStatus.FAILED,
    )
}

object TransferStateMachine {
    private val transitions = mapOf(
        TransferStatus.QUEUED to setOf(
            TransferStatus.RUNNING,
            TransferStatus.PAUSED,
            TransferStatus.WAITING_FOR_NETWORK,
            TransferStatus.CANCELED,
        ),
        TransferStatus.RUNNING to setOf(
            TransferStatus.PAUSED,
            TransferStatus.WAITING_FOR_NETWORK,
            TransferStatus.SUCCESS,
            TransferStatus.FAILED,
            TransferStatus.CANCELED,
        ),
        TransferStatus.PAUSED to setOf(
            TransferStatus.QUEUED,
            TransferStatus.RUNNING,
            TransferStatus.CANCELED,
        ),
        TransferStatus.WAITING_FOR_NETWORK to setOf(
            TransferStatus.QUEUED,
            TransferStatus.RUNNING,
            TransferStatus.CANCELED,
        ),
        TransferStatus.WAITING_FOR_RETRY to setOf(
            TransferStatus.FAILED,
            TransferStatus.CANCELED,
        ),
        TransferStatus.FAILED to setOf(TransferStatus.CANCELED),
        TransferStatus.SUCCESS to emptySet(),
        TransferStatus.CANCELED to emptySet(),
    )

    fun canTransition(from: TransferStatus, to: TransferStatus): Boolean =
        (from == to && from != TransferStatus.WAITING_FOR_RETRY) ||
            to in transitions.getValue(from)

    fun requireTransition(from: TransferStatus, to: TransferStatus) {
        require(canTransition(from, to)) { "Invalid transfer state transition: $from -> $to" }
    }

    fun canScheduleRetry(from: TransferStatus): Boolean = from == TransferStatus.RUNNING

    fun canRetry(from: TransferStatus): Boolean =
        from == TransferStatus.WAITING_FOR_RETRY || from == TransferStatus.FAILED
}

object TransferProgressValidator {
    @Suppress("LongParameterList")
    fun requireValid(
        previousBytes: Long,
        nextBytes: Long,
        totalBytes: Long,
        previousChunk: Int,
        nextChunk: Int,
        totalChunks: Int,
        speedBytesPerSecond: Long,
    ) {
        require(previousBytes >= 0 && nextBytes in previousBytes..totalBytes) {
            "Transfer byte progress must be monotonic and within its total"
        }
        require(previousChunk >= 0 && nextChunk in previousChunk..totalChunks) {
            "Transfer chunk progress must be monotonic and within its total"
        }
        require(speedBytesPerSecond >= 0) { "Transfer speed cannot be negative" }
    }
}
