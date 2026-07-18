package com.teledrive.lite.deletion

import com.teledrive.lite.repository.DriveRepositoryException
import com.teledrive.lite.repository.DriveRepositoryFailure

object DeletionStartRecovery {
    suspend fun runBatch(
        fileIds: List<String>,
        enqueue: suspend (List<String>) -> Unit,
        synchronizeIndex: suspend () -> Unit,
    ) {
        val distinctIds = fileIds.filter(String::isNotBlank).distinct()
        if (distinctIds.isEmpty()) return
        run(
            enqueue = { enqueue(distinctIds) },
            synchronizeIndex = synchronizeIndex,
        )
    }

    suspend fun run(
        enqueue: suspend () -> Unit,
        synchronizeIndex: suspend () -> Unit,
    ) {
        try {
            enqueue()
        } catch (error: DriveRepositoryException) {
            if (error.failure != DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED) {
                throw error
            }
            synchronizeIndex()
            enqueue()
        }
    }
}
