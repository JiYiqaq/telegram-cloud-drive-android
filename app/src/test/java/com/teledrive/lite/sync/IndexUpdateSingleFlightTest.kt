package com.teledrive.lite.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexUpdateSingleFlightTest {
    @Test
    fun concurrentCallersShareTheSameActiveUpdateAndLaterCallsStartAgain() = runBlocking {
        val singleFlight = IndexUpdateSingleFlight()
        val updateStarted = CompletableDeferred<Unit>()
        val allowUpdateToFinish = CompletableDeferred<Unit>()
        var executions = 0
        val firstOutcome = completedOutcome(revision = 5)

        val first = async {
            singleFlight.run {
                executions += 1
                updateStarted.complete(Unit)
                allowUpdateToFinish.await()
                firstOutcome
            }
        }
        updateStarted.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.run {
                executions += 1
                completedOutcome(revision = 99)
            }
        }

        allowUpdateToFinish.complete(Unit)

        assertEquals(firstOutcome, first.await())
        assertEquals(firstOutcome, second.await())
        assertEquals(1, executions)

        val laterOutcome = singleFlight.run {
            executions += 1
            completedOutcome(revision = 6)
        }
        assertEquals(6L, laterOutcome.stableState.revision)
        assertEquals(2, executions)
    }

    @Test
    fun activeWaiterTakesOverAfterOwnerCancellationAndFutureCallsRemainUsable() = runBlocking {
        val singleFlight = IndexUpdateSingleFlight()
        val ownerStarted = CompletableDeferred<Unit>()
        var replacementExecuted = false

        val owner = async {
            singleFlight.run {
                ownerStarted.complete(Unit)
                awaitCancellation()
            }
        }
        ownerStarted.await()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.run {
                replacementExecuted = true
                completedOutcome(revision = 6)
            }
        }

        owner.cancelAndJoin()

        assertEquals(6L, waiter.await().stableState.revision)
        assertTrue(replacementExecuted)

        val later = singleFlight.run {
            completedOutcome(revision = 7)
        }
        assertEquals(7L, later.stableState.revision)
    }

    private fun completedOutcome(revision: Long) = IndexUpdateOutcome.Completed(
        stableState = StableIndexState(
            revision = revision,
            messageId = 100 + revision,
            fileId = "index-$revision",
        ),
    )
}
