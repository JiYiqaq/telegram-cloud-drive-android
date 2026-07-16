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

class FolderDeletionScheduler(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) {
    fun enqueue(folderId: String) {
        require(folderId.isNotBlank())
        val request = OneTimeWorkRequestBuilder<FolderDeletionWorker>()
            .setInputData(Data.Builder().putString(FolderDeletionWorker.KEY_FOLDER_ID, folderId).build())
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("teledrive_folder_deletion")
            .build()
        workManager.enqueueUniqueWork(
            "folder-delete:$folderId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
