package com.teledrive.lite.deletion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DeletionWorkPersistenceTest {
    @Test
    fun persistenceFailureRestoresVisibleRecoverableState() {
        var recoveries = 0

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                DeletionWorkPersistence.await(
                    waitForPersistence = { throw IllegalStateException("enqueue failed") },
                    recover = { recoveries += 1 },
                )
            }
        }

        assertEquals(1, recoveries)
    }

    @Test
    fun callerCancellationAfterPersistenceDoesNotResurrectDeletion() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val persisted = CompletableDeferred<Unit>()
        var recovered = false
        val caller = launch {
            DeletionWorkPersistence.await(
                waitForPersistence = {
                    started.complete(Unit)
                    persisted.await()
                },
                recover = { recovered = true },
            )
        }

        started.await()
        caller.cancel()
        persisted.complete(Unit)
        caller.cancelAndJoin()

        assertFalse(recovered)
    }
}
