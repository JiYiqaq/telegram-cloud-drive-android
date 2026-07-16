package com.teledrive.lite.download

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.teledrive.lite.TeleDriveApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_DOWNLOAD) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf(String::isNotBlank) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val application = context.applicationContext as? TeleDriveApplication
                runCatching { application?.container?.downloadScheduler?.cancel(taskId) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_CANCEL_DOWNLOAD = "com.teledrive.lite.action.CANCEL_DOWNLOAD"
        private const val EXTRA_TASK_ID = "task_id"

        fun pendingIntent(context: Context, taskId: String): PendingIntent {
            require(taskId.isNotBlank())
            val intent = Intent(context, DownloadCancelReceiver::class.java)
                .setAction(ACTION_CANCEL_DOWNLOAD)
                .setData(
                    Uri.Builder()
                        .scheme("teledrive")
                        .authority("download")
                        .appendPath("cancel")
                        .appendPath(taskId)
                        .build(),
                )
                .putExtra(EXTRA_TASK_ID, taskId)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
