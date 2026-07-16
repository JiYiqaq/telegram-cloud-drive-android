package com.teledrive.lite.ui.home

import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePresentationTest {
    @Test
    fun fileStatusesUseClearChineseLabels() {
        assertEquals(
            listOf(
                "等待上传",
                "正在加密",
                "正在上传",
                "可用",
                "正在下载",
                "操作失败",
                "正在删除",
                "删除未完成",
                "文件损坏",
            ),
            FileStatus.entries.map(HomePresentation::fileStatusLabel),
        )
    }

    @Test
    fun transferTypesAndStatusesUseClearChineseLabels() {
        assertEquals(listOf("上传", "下载"), TransferType.entries.map(HomePresentation::transferTypeLabel))
        assertEquals(
            listOf(
                "等待开始",
                "进行中",
                "已暂停",
                "等待网络",
                "等待重试",
                "已完成",
                "失败",
                "已取消",
            ),
            TransferStatus.entries.map(HomePresentation::transferStatusLabel),
        )
    }

    @Test
    fun successfulFileDeletionConfirmsRemovalFromTheList() {
        assertEquals(
            "删除成功：文件已从列表移除，正在后台安全清理云端数据",
            HomePresentation.deletionMessage(queuedFiles = 1, queuedFolders = 0, failed = 0),
        )
        assertEquals(
            "删除成功：3 个文件已从列表移除，正在后台安全清理云端数据",
            HomePresentation.deletionMessage(queuedFiles = 3, queuedFolders = 0, failed = 0),
        )
    }

    @Test
    fun folderAndPartialDeletionMessagesDoNotClaimPrematureCompletion() {
        assertEquals(
            "文件夹删除任务已提交，完成后将自动从列表移除",
            HomePresentation.deletionMessage(queuedFiles = 0, queuedFolders = 1, failed = 0),
        )
        assertEquals(
            "已删除 2 个文件并提交 1 个文件夹删除任务；后台正在安全清理云端数据",
            HomePresentation.deletionMessage(queuedFiles = 2, queuedFolders = 1, failed = 0),
        )
        assertEquals(
            "已删除 1 个文件并从列表移除，2 项删除失败",
            HomePresentation.deletionMessage(queuedFiles = 1, queuedFolders = 0, failed = 2),
        )
        assertEquals(
            "删除失败，请检查文件状态后重试",
            HomePresentation.deletionMessage(queuedFiles = 0, queuedFolders = 0, failed = 2),
        )
    }
}
