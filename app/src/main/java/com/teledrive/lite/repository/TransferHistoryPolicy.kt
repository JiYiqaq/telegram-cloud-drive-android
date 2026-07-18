package com.teledrive.lite.repository

import com.teledrive.lite.database.TransferTaskEntity
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType

object TransferHistoryPolicy {
    private val terminalStatuses = setOf(
        TransferStatus.SUCCESS,
        TransferStatus.FAILED,
        TransferStatus.CANCELED,
    )

    fun isTerminal(status: TransferStatus): Boolean = status in terminalStatuses

    fun canDismiss(task: TransferTaskEntity): Boolean = when (task.status) {
        TransferStatus.SUCCESS -> true
        TransferStatus.FAILED,
        TransferStatus.CANCELED,
        -> task.type == TransferType.DOWNLOAD || task.fileId == null

        else -> false
    }
}
