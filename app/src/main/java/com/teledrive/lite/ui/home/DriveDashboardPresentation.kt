package com.teledrive.lite.ui.home

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.teledrive.lite.database.TransferTaskEntity
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.repository.TransferHistoryPolicy

data class DriveHeroPalette(
    val start: Color,
    val end: Color,
    val content: Color,
)

enum class HomeSection {
    FILES,
    TRANSFERS,
}

object DriveDashboardPresentation {
    fun heroPalette(colors: ColorScheme): DriveHeroPalette = DriveHeroPalette(
        start = colors.primary,
        end = colors.secondary,
        content = colors.onPrimary,
    )

    fun activeTransfers(tasks: List<TransferTaskEntity>): List<TransferTaskEntity> =
        tasks.filterNot { TransferHistoryPolicy.isTerminal(it.status) }

    fun historyTransfers(tasks: List<TransferTaskEntity>): List<TransferTaskEntity> =
        tasks.filter { TransferHistoryPolicy.isTerminal(it.status) }

    fun historyCount(tasks: List<TransferTaskEntity>): Int = historyTransfers(tasks).size

    fun clearedHistoryMessage(removed: Int): String {
        require(removed >= 0)
        return if (removed == 0) {
            "没有可清理的传输记录"
        } else {
            "已清理 $removed 条传输记录；云端文件不受影响"
        }
    }

    fun dismissedTransferMessage(): String = "已删除传输记录；云端文件不受影响"

    fun fileStatusGuidance(status: FileStatus): String? = when (status) {
        FileStatus.PARTIALLY_DELETED -> "删除未完成 · 请检查 Bot 删除消息权限或网络后重试"
        else -> null
    }
}
