package com.teledrive.lite.repository

import com.teledrive.lite.database.TransferTaskEntity
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferHistorySafetyTest {
    @Test
    fun failedUploadMustKeepItsRecordUntilOrphanChunksAreCleaned() {
        assertFalse(
            TransferHistoryPolicy.canDismiss(
                task(TransferType.UPLOAD, TransferStatus.FAILED, fileId = "local-upload"),
            ),
        )
        assertFalse(
            TransferHistoryPolicy.canDismiss(
                task(TransferType.UPLOAD, TransferStatus.CANCELED, fileId = "local-upload"),
            ),
        )
        assertTrue(
            TransferHistoryPolicy.canDismiss(
                task(TransferType.UPLOAD, TransferStatus.FAILED, fileId = null),
            ),
        )
    }

    @Test
    fun successAndStoppedDownloadsRemainDismissible() {
        assertTrue(
            TransferHistoryPolicy.canDismiss(
                task(TransferType.UPLOAD, TransferStatus.SUCCESS, fileId = "cloud-file"),
            ),
        )
        assertTrue(
            TransferHistoryPolicy.canDismiss(
                task(TransferType.DOWNLOAD, TransferStatus.FAILED, fileId = "cloud-file"),
            ),
        )
        assertTrue(
            TransferHistoryPolicy.canDismiss(
                task(TransferType.DOWNLOAD, TransferStatus.CANCELED, fileId = "cloud-file"),
            ),
        )
        assertFalse(
            TransferHistoryPolicy.canDismiss(
                task(TransferType.DOWNLOAD, TransferStatus.RUNNING, fileId = "cloud-file"),
            ),
        )
    }

    private fun task(
        type: TransferType,
        status: TransferStatus,
        fileId: String?,
    ) = TransferTaskEntity(
        id = "$type-$status-${fileId ?: "clean"}",
        fileId = fileId,
        fileNameSnapshot = "sample.bin",
        type = type,
        status = status,
        completedBytes = 0,
        totalBytes = 1,
        currentChunk = 0,
        totalChunks = 1,
        speedBytesPerSecond = 0,
        attempt = 0,
        nextRetryAtEpochMillis = null,
        errorCode = null,
        workRequestId = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )
}
