package com.teledrive.lite.upload

import com.teledrive.lite.sync.IndexUpdateOutcome
import com.teledrive.lite.sync.StableIndexState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RequiredFileIndexPublisherTest {
    @Test
    fun continuesAfterAConcurrentStaleRevisionUntilTargetFileIsActuallyPublished() = runBlocking {
        val outcomes = ArrayDeque(
            listOf(
                IndexUpdateOutcome.Completed(StableIndexState(1, 10, "index-1"), emptySet()),
                IndexUpdateOutcome.Completed(StableIndexState(2, 20, "index-2"), setOf("file")),
            ),
        )
        var attempts = 0
        val publisher = RequiredFileIndexPublisher(
            runner = CloudIndexUpdateRunner {
                attempts += 1
                outcomes.removeFirst()
            },
        )

        publisher.publish("file")

        assertEquals(2, attempts)
    }
}
