package com.teledrive.lite.deletion

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class OrphanCleanupScheduler(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) {
    fun enqueue(taskId: String) {
        require(taskId.isNotBlank())
        val request = OneTimeWorkRequestBuilder<OrphanCleanupWorker>()
            .setInputData(Data.Builder().putString(OrphanCleanupWorker.KEY_TASK_ID, taskId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ORPHAN_CLEANUP_TAG)
            .addTag("orphan-cleanup:$taskId")
            .build()
        workManager.enqueueUniqueWork(
            "orphan-cleanup:$taskId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val ORPHAN_CLEANUP_TAG = "teledrive_orphan_cleanup"
    }
}
