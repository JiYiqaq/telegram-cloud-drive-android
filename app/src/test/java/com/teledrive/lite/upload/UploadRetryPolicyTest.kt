package com.teledrive.lite.upload

import com.teledrive.lite.database.TransferTaskEntity
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRetryPolicyTest {
    @Test
    fun deniesEveryFailureThatCouldDuplicateOrCannotReuseTheSelectedSource() {
        listOf(
            UploadRetryPolicy.RESULT_UNKNOWN_ERROR,
            UploadRetryPolicy.REMOTE_RESULT_UNKNOWN_ERROR,
            UploadRetryPolicy.SOURCE_CHANGED_ERROR,
            UploadRetryPolicy.SOURCE_UNAVAILABLE_ERROR,
        ).forEach { error ->
            assertFalse(UploadRetryPolicy.canRetry(task(TransferStatus.FAILED, error)))
        }
    }

    @Test
    fun allowsSafeFailedAndCanceledUploadsButRateLimitRetriesAutomatically() {
        assertTrue(UploadRetryPolicy.canRetry(task(TransferStatus.FAILED, "TELEGRAM_REJECTED")))
        assertTrue(UploadRetryPolicy.canRetry(task(TransferStatus.CANCELED, "USER_CANCELED")))
        assertFalse(UploadRetryPolicy.canRetry(task(TransferStatus.WAITING_FOR_RETRY, "RATE_LIMITED")))
    }

    private fun task(status: TransferStatus, errorCode: String) = TransferTaskEntity(
        id = "task",
        fileId = "file",
        fileNameSnapshot = "x.bin",
        type = TransferType.UPLOAD,
        status = status,
        completedBytes = 0,
        totalBytes = 1,
        currentChunk = 0,
        totalChunks = 1,
        speedBytesPerSecond = 0,
        attempt = 0,
        nextRetryAtEpochMillis = null,
        errorCode = errorCode,
        workRequestId = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        sourceUri = "content://x",
    )
}
