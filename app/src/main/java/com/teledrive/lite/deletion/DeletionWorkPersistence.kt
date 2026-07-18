package com.teledrive.lite.deletion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

object DeletionWorkPersistence {
    suspend fun await(
        waitForPersistence: suspend () -> Unit,
        recover: suspend () -> Unit,
    ) {
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                waitForPersistence()
            } catch (error: Exception) {
                try {
                    recover()
                } catch (recoveryError: Exception) {
                    error.addSuppressed(recoveryError)
                }
                throw error
            }
        }
    }

    suspend fun restore(recover: suspend () -> Unit) {
        withContext(NonCancellable) { recover() }
    }
}
