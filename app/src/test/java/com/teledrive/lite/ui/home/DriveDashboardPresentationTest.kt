package com.teledrive.lite.ui.home

import com.teledrive.lite.database.TransferTaskEntity
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveDashboardPresentationTest {
    @Test
    fun transfersAreSeparatedIntoActiveAndHistorySections() {
        val tasks = TransferStatus.entries.mapIndexed { index, status ->
            task(id = "task-$index", status = status)
        }

        assertEquals(
            listOf(
                TransferStatus.QUEUED,
                TransferStatus.RUNNING,
                TransferStatus.PAUSED,
                TransferStatus.WAITING_FOR_NETWORK,
                TransferStatus.WAITING_FOR_RETRY,
            ),
            DriveDashboardPresentation.activeTransfers(tasks).map(TransferTaskEntity::status),
        )
        assertEquals(
            listOf(
                TransferStatus.SUCCESS,
                TransferStatus.FAILED,
                TransferStatus.CANCELED,
            ),
            DriveDashboardPresentation.historyTransfers(tasks).map(TransferTaskEntity::status),
        )
        assertEquals(3, DriveDashboardPresentation.historyCount(tasks))
    }

    @Test
    fun historyRemovalMessagesExplainThatCloudFilesRemain() {
        assertEquals(
            "没有可清理的传输记录",
            DriveDashboardPresentation.clearedHistoryMessage(0),
        )
        assertEquals(
            "已清理 3 条传输记录；云端文件不受影响",
            DriveDashboardPresentation.clearedHistoryMessage(3),
        )
        assertEquals(
            "已删除传输记录；云端文件不受影响",
            DriveDashboardPresentation.dismissedTransferMessage(),
        )
    }

    @Test
    fun partiallyDeletedFilesShowActionableGuidance() {
        assertEquals(
            "删除未完成 · 请检查 Bot 删除消息权限或网络后重试",
            DriveDashboardPresentation.fileStatusGuidance(FileStatus.PARTIALLY_DELETED),
        )
        assertNull(DriveDashboardPresentation.fileStatusGuidance(FileStatus.AVAILABLE))
    }

    @Test
    fun bottomNavigationKeepsFilesAndTransfersAsDistinctSections() {
        assertEquals(
            listOf(HomeSection.FILES, HomeSection.TRANSFERS),
            HomeSection.entries,
        )
    }

    private fun task(id: String, status: TransferStatus) = TransferTaskEntity(
        id = id,
        fileId = null,
        fileNameSnapshot = "$id.bin",
        type = TransferType.UPLOAD,
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
