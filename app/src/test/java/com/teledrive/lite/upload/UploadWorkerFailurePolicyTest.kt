package com.teledrive.lite.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadWorkerFailurePolicyTest {
    @Test
    fun `unknown remote result becomes explicit non retryable failure`() {
        assertEquals(
            UploadRetryPolicy.REMOTE_RESULT_UNKNOWN_ERROR,
            UploadWorkerFailurePolicy.loadErrorCode(
                UploadException(UploadFailure.REMOTE_RESULT_UNKNOWN),
            ),
        )
    }

    @Test
    fun `invalid durable task gets a stable failure code`() {
        assertEquals(
            UploadWorkerFailurePolicy.INVALID_TASK_ERROR,
            UploadWorkerFailurePolicy.loadErrorCode(IllegalStateException("invalid")),
        )
    }
}
