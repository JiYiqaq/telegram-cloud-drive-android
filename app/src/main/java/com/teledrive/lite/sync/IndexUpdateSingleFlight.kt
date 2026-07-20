package com.teledrive.lite.sync

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coalesces concurrent index-update calls so independent service objects never resume the same
 * durable journal at the same time. A new call starts normally after the active call completes.
 */
class IndexUpdateSingleFlight {
    private val stateMutex = Mutex()
    private var active: CompletableDeferred<IndexUpdateOutcome.Completed>? = null

    suspend fun run(
        update: suspend () -> IndexUpdateOutcome.Completed,
    ): IndexUpdateOutcome.Completed {
        while (true) {
            val (flight, isOwner) = joinOrCreate()
            if (!isOwner) {
                try {
                    return flight.await()
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    clearIfCurrent(flight)
                    continue
                }
            }

            try {
                return update().also(flight::complete)
            } catch (error: Throwable) {
                flight.completeExceptionally(error)
                throw error
            } finally {
                withContext(NonCancellable) {
                    clearIfCurrent(flight)
                }
            }
        }
    }

    private suspend fun joinOrCreate():
        Pair<CompletableDeferred<IndexUpdateOutcome.Completed>, Boolean> {
        val candidate = CompletableDeferred<IndexUpdateOutcome.Completed>()
        return stateMutex.withLock {
            active?.let { it to false }
                ?: candidate.also { active = it }.let { it to true }
        }
    }

    private suspend fun clearIfCurrent(
        flight: CompletableDeferred<IndexUpdateOutcome.Completed>,
    ) {
        stateMutex.withLock {
            if (active === flight) active = null
        }
    }
}
