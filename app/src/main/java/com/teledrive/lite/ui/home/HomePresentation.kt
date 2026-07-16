package com.teledrive.lite.ui.home

import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType

object HomePresentation {
    fun fileStatusLabel(status: FileStatus): String = when (status) {
        FileStatus.PENDING -> "等待上传"
        FileStatus.ENCRYPTING -> "正在加密"
        FileStatus.UPLOADING -> "正在上传"
        FileStatus.AVAILABLE -> "可用"
        FileStatus.DOWNLOADING -> "正在下载"
        FileStatus.FAILED -> "操作失败"
        FileStatus.DELETING -> "正在删除"
        FileStatus.PARTIALLY_DELETED -> "删除未完成"
        FileStatus.CORRUPTED -> "文件损坏"
    }

    fun transferStatusLabel(status: TransferStatus): String = when (status) {
        TransferStatus.QUEUED -> "等待开始"
        TransferStatus.RUNNING -> "进行中"
        TransferStatus.PAUSED -> "已暂停"
        TransferStatus.WAITING_FOR_NETWORK -> "等待网络"
        TransferStatus.WAITING_FOR_RETRY -> "等待重试"
        TransferStatus.SUCCESS -> "已完成"
        TransferStatus.FAILED -> "失败"
        TransferStatus.CANCELED -> "已取消"
    }

    fun transferTypeLabel(type: TransferType): String = when (type) {
        TransferType.UPLOAD -> "上传"
        TransferType.DOWNLOAD -> "下载"
    }

    fun deletionMessage(queuedFiles: Int, queuedFolders: Int, failed: Int): String {
        require(queuedFiles >= 0 && queuedFolders >= 0 && failed >= 0)
        return when {
            queuedFiles == 0 && queuedFolders == 0 -> "删除失败，请检查文件状态后重试"
            queuedFolders == 0 && failed == 0 && queuedFiles == 1 ->
                "删除成功：文件已从列表移除，正在后台安全清理云端数据"
            queuedFolders == 0 && failed == 0 ->
                "删除成功：$queuedFiles 个文件已从列表移除，正在后台安全清理云端数据"
            queuedFolders == 0 ->
                "已删除 $queuedFiles 个文件并从列表移除，$failed 项删除失败"
            queuedFiles == 0 && failed == 0 && queuedFolders == 1 ->
                "文件夹删除任务已提交，完成后将自动从列表移除"
            queuedFiles == 0 && failed == 0 ->
                "已提交 $queuedFolders 个文件夹删除任务，完成后将自动从列表移除"
            queuedFiles == 0 ->
                "已提交 $queuedFolders 个文件夹删除任务，$failed 项删除失败"
            failed == 0 ->
                "已删除 $queuedFiles 个文件并提交 $queuedFolders 个文件夹删除任务；" +
                    "后台正在安全清理云端数据"
            else ->
                "已删除 $queuedFiles 个文件并提交 $queuedFolders 个文件夹删除任务，" +
                    "$failed 项删除失败"
        }
    }
}
