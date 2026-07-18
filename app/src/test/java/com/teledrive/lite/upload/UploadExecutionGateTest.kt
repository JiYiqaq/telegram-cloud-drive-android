package com.teledrive.lite.upload

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadExecutionGateTest {
    @Test
    fun `uploads execute one at a time`() = runBlocking {
        val gate = UploadExecutionGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        val first = async {
            gate.withPermit {
                val count = active.incrementAndGet()
                maxActive.updateAndGet { current -> maxOf(current, count) }
                firstEntered.complete(Unit)
                releaseFirst.await()
                active.decrementAndGet()
            }
        }
        firstEntered.await()

        val second = async {
            gate.withPermit {
                val count = active.incrementAndGet()
                maxActive.updateAndGet { current -> maxOf(current, count) }
                secondEntered.complete(Unit)
                active.decrementAndGet()
            }
        }

        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertTrue(secondEntered.isCompleted)
        assertEquals(1, maxActive.get())
    }

    @Test
    fun `failed upload does not poison the next upload`() = runBlocking {
        val gate = UploadExecutionGate()
        val firstFailure = runCatching {
            gate.withPermit<Unit> { error("first upload failed") }
        }
        var secondRan = false

        gate.withPermit { secondRan = true }

        assertTrue(firstFailure.isFailure)
        assertTrue(secondRan)
    }
}
