package com.teledrive.lite.deletion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DeletionBatchProcessorTest {
    @Test
    fun firstFailureIsRecoveredWithoutBlockingSecondFile() = runBlocking {
        val executed = mutableListOf<String>()
        val recovered = mutableListOf<String>()

        val result = DeletionBatchProcessor(
            execute = { fileId ->
                executed += fileId
                if (fileId == "file-1") error("first deletion failed")
                DeletionOutcome.Completed
            },
            recover = { fileId, _ -> recovered += fileId },
        ).run(listOf("file-1", "file-2"))

        assertEquals(listOf("file-1", "file-2"), executed)
        assertEquals(listOf("file-1"), recovered)
        assertEquals(1, result.completed)
        assertEquals(1, result.failed)
    }

    @Test
    fun retryableFailureIsReportedAfterFollowingFileCompletes() = runBlocking {
        val executed = mutableListOf<String>()

        val result = DeletionBatchProcessor(
            execute = { fileId ->
                executed += fileId
                if (fileId == "file-1") error("retry this file")
                DeletionOutcome.Completed
            },
            recover = { _, _ -> },
            retryDelaySeconds = { error ->
                if (error.message == "retry this file") 45L else null
            },
        ).run(listOf("file-1", "file-2"))

        assertEquals(listOf("file-1", "file-2"), executed)
        assertEquals(listOf("file-1"), result.retryableFileIds)
        assertEquals(45L, result.retryDelaySeconds)
        assertEquals(1, result.completed)
        assertEquals(1, result.failed)
    }

    @Test
    fun partialDeletionDoesNotBlockLaterFiles() = runBlocking {
        val executed = mutableListOf<String>()

        val result = DeletionBatchProcessor(
            execute = { fileId ->
                executed += fileId
                if (fileId == "file-1") {
                    DeletionOutcome.PartiallyDeleted(failedChunks = 1)
                } else {
                    DeletionOutcome.Completed
                }
            },
            recover = { _, _ -> error("partial outcomes are already persisted") },
        ).run(listOf("file-1", "file-2"))

        assertEquals(listOf("file-1", "file-2"), executed)
        assertEquals(1, result.completed)
        assertEquals(1, result.failed)
    }
}
