package com.teledrive.lite.upload

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UploadQueueMutationGateTest {
    @Test
    fun `migration and new enqueue cannot interleave durable work identity changes`() = runBlocking {
        val gate = UploadQueueMutationGate()
        val migrationEntered = CompletableDeferred<Unit>()
        val finishMigration = CompletableDeferred<Unit>()
        val enqueueEntered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val migration = async {
            gate.withLock {
                events += "migration-start"
                migrationEntered.complete(Unit)
                finishMigration.await()
                events += "migration-end"
            }
        }
        migrationEntered.await()

        val enqueue = async {
            gate.withLock {
                events += "enqueue-start"
                enqueueEntered.complete(Unit)
            }
        }

        assertFalse(enqueueEntered.isCompleted)
        finishMigration.complete(Unit)
        migration.await()
        enqueue.await()

        assertEquals(
            listOf("migration-start", "migration-end", "enqueue-start"),
            events,
        )
    }
}
