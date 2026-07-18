package com.teledrive.lite.deletion

import com.teledrive.lite.repository.DriveRepositoryException
import com.teledrive.lite.repository.DriveRepositoryFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DeletionStartRecoveryTest {
    @Test
    fun dirtyIndexIsSynchronizedBeforeOneDeletionRetry() = runBlocking {
        var enqueueAttempts = 0
        var syncAttempts = 0

        DeletionStartRecovery.run(
            enqueue = {
                enqueueAttempts += 1
                if (enqueueAttempts == 1) {
                    throw DriveRepositoryException(
                        DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED,
                    )
                }
            },
            synchronizeIndex = { syncAttempts += 1 },
        )

        assertEquals(2, enqueueAttempts)
        assertEquals(1, syncAttempts)
    }

    @Test
    fun nonIndexFailureIsRethrownWithoutSynchronization() {
        val expected = DriveRepositoryException(DriveRepositoryFailure.INVALID_FILE_STATE)
        var syncAttempts = 0

        val actual = assertThrows(DriveRepositoryException::class.java) {
            runBlocking {
                DeletionStartRecovery.run(
                    enqueue = { throw expected },
                    synchronizeIndex = { syncAttempts += 1 },
                )
            }
        }

        assertSame(expected, actual)
        assertEquals(0, syncAttempts)
    }

    @Test
    fun secondDeletionFailureIsRethrownWithoutLooping() {
        var enqueueAttempts = 0
        val expected = DriveRepositoryException(DriveRepositoryFailure.ACTIVE_TRANSFER_EXISTS)

        val actual = assertThrows(DriveRepositoryException::class.java) {
            runBlocking {
                DeletionStartRecovery.run(
                    enqueue = {
                        enqueueAttempts += 1
                        if (enqueueAttempts == 1) {
                            throw DriveRepositoryException(
                                DriveRepositoryFailure.INDEX_CONFIRMATION_REQUIRED,
                            )
                        }
                        throw expected
                    },
                    synchronizeIndex = {},
                )
            }
        }

        assertSame(expected, actual)
        assertEquals(2, enqueueAttempts)
    }
}
