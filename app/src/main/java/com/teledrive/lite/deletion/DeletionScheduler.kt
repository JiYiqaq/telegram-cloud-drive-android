package com.teledrive.lite.deletion

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.teledrive.lite.repository.FileRepository
import java.util.UUID
import java.util.concurrent.TimeUnit

class DeletionScheduler(
    context: Context,
    private val repository: FileRepository,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) {
    suspend fun enqueue(fileId: String) {
        val start = repository.beginFileDeletion(fileId)
        start.canceledWorkRequestIds.forEach { workId ->
            runCatching { workManager.cancelWorkById(UUID.fromString(workId)) }
        }
        val request = OneTimeWorkRequestBuilder<DeletionWorker>()
            .setInputData(Data.Builder().putString(DeletionWorker.KEY_FILE_ID, fileId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DELETION_TAG)
            .addTag("delete:$fileId")
            .build()
        workManager.enqueueUniqueWork(
            "safe-delete:$fileId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val DELETION_TAG = "teledrive_safe_deletion"
    }
}
