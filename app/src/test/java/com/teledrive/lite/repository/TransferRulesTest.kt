package com.teledrive.lite.repository

import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRulesTest {
    @Test
    fun fileEligibilityRejectsDeletionAndWrongCloudMembership() {
        assertTrue(
            TransferFileEligibility.canExecute(TransferType.UPLOAD, FileStatus.UPLOADING, false),
        )
        assertTrue(
            TransferFileEligibility.canExecute(TransferType.DOWNLOAD, FileStatus.AVAILABLE, true),
        )
        assertFalse(
            TransferFileEligibility.canExecute(TransferType.UPLOAD, FileStatus.DELETING, true),
        )
        assertFalse(
            TransferFileEligibility.canExecute(TransferType.DOWNLOAD, FileStatus.PARTIALLY_DELETED, true),
        )
    }

    @Test
    fun stateMachineAllowsNetworkRecoveryButReservesRetryForExplicitPaths() {
        assertTrue(TransferStateMachine.canTransition(TransferStatus.QUEUED, TransferStatus.RUNNING))
        assertTrue(
            TransferStateMachine.canTransition(
                TransferStatus.RUNNING,
                TransferStatus.WAITING_FOR_NETWORK,
            ),
        )
        assertTrue(
            TransferStateMachine.canTransition(
                TransferStatus.WAITING_FOR_NETWORK,
                TransferStatus.RUNNING,
            ),
        )
        assertFalse(
            TransferStateMachine.canTransition(
                TransferStatus.WAITING_FOR_RETRY,
                TransferStatus.RUNNING,
            ),
        )
        assertFalse(TransferStateMachine.canTransition(TransferStatus.FAILED, TransferStatus.QUEUED))
        assertFalse(
            TransferStateMachine.canTransition(
                TransferStatus.WAITING_FOR_RETRY,
                TransferStatus.WAITING_FOR_RETRY,
            ),
        )
        assertTrue(TransferStateMachine.canScheduleRetry(TransferStatus.RUNNING))
        assertTrue(TransferStateMachine.canRetry(TransferStatus.WAITING_FOR_RETRY))
        assertTrue(TransferStateMachine.canRetry(TransferStatus.FAILED))
    }

    @Test
    fun terminalStatesCannotRestartWithoutAnExplicitRetryableFailure() {
        assertFalse(TransferStateMachine.canTransition(TransferStatus.SUCCESS, TransferStatus.RUNNING))
        assertFalse(TransferStateMachine.canTransition(TransferStatus.CANCELED, TransferStatus.RUNNING))
        assertThrows(IllegalArgumentException::class.java) {
            TransferStateMachine.requireTransition(TransferStatus.SUCCESS, TransferStatus.RUNNING)
        }
    }

    @Test
    fun progressValidationRejectsRegressionAndOutOfBoundsValues() {
        TransferProgressValidator.requireValid(
            previousBytes = 128,
            nextBytes = 256,
            totalBytes = 512,
            previousChunk = 1,
            nextChunk = 2,
            totalChunks = 4,
            speedBytesPerSecond = 1024,
        )

        assertThrows(IllegalArgumentException::class.java) {
            TransferProgressValidator.requireValid(256, 128, 512, 2, 2, 4, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferProgressValidator.requireValid(128, 513, 512, 1, 2, 4, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferProgressValidator.requireValid(128, 256, 512, 2, 1, 4, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferProgressValidator.requireValid(128, 256, 512, 1, 2, 4, -1)
        }
    }
}
