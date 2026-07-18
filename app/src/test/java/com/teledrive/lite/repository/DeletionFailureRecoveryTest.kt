package com.teledrive.lite.repository

import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.PendingOperationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeletionFailureRecoveryTest {
    @Test
    fun terminalFailureBecomesVisibleAndKeepsRetryMetadata() {
        val transition = DeletionFailureRecovery.transition(
            currentFileStatus = FileStatus.DELETING,
            currentAttempt = 2,
            errorCode = "WORK_NOT_ENQUEUED",
        )

        assertEquals(FileStatus.PARTIALLY_DELETED, transition.fileStatus)
        assertEquals(PendingOperationStatus.FAILED, transition.operationStatus)
        assertEquals(3, transition.attempt)
        assertEquals("WORK_NOT_ENQUEUED", transition.errorCode)
    }

    @Test
    fun retryFailureRemainsRecoverableAndRejectsInvalidInputs() {
        assertEquals(
            5,
            DeletionFailureRecovery.transition(
                currentFileStatus = FileStatus.PARTIALLY_DELETED,
                currentAttempt = 4,
                errorCode = "DELETE_FAILED",
            ).attempt,
        )
        assertThrows(IllegalArgumentException::class.java) {
            DeletionFailureRecovery.transition(FileStatus.AVAILABLE, 0, "DELETE_FAILED")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeletionFailureRecovery.transition(FileStatus.DELETING, 0, " ")
        }
    }

    @Test
    fun explicitRetryReturnsRecoverableFailureToActiveHiddenDeletion() {
        val transition = DeletionFailureRecovery.retryTransition(
            FileStatus.PARTIALLY_DELETED,
        )

        assertEquals(FileStatus.DELETING, transition.fileStatus)
        assertEquals(PendingOperationStatus.PENDING, transition.operationStatus)
        assertEquals(null, transition.errorCode)
        assertThrows(IllegalArgumentException::class.java) {
            DeletionFailureRecovery.retryTransition(FileStatus.DELETING)
        }
    }
}
