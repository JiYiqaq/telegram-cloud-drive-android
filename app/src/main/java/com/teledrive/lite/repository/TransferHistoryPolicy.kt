package com.teledrive.lite.repository

import com.teledrive.lite.model.TransferStatus

object TransferHistoryPolicy {
    private val terminalStatuses = setOf(
        TransferStatus.SUCCESS,
        TransferStatus.FAILED,
        TransferStatus.CANCELED,
    )

    fun canDismiss(status: TransferStatus): Boolean = status in terminalStatuses
}
