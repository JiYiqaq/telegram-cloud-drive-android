package com.teledrive.lite.repository

import com.teledrive.lite.model.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferHistoryPolicyTest {
    @Test
    fun onlyTerminalTransferRecordsCanBeDismissed() {
        val dismissible = TransferStatus.entries.filter(TransferHistoryPolicy::canDismiss)

        assertEquals(
            listOf(
                TransferStatus.SUCCESS,
                TransferStatus.FAILED,
                TransferStatus.CANCELED,
            ),
            dismissible,
        )
    }
}
