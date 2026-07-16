package com.teledrive.lite.upload

import com.teledrive.lite.sync.IndexUpdateOutcome

fun interface CloudIndexUpdateRunner {
    suspend fun update(): IndexUpdateOutcome.Completed
}

class RequiredFileIndexPublisher(
    private val runner: CloudIndexUpdateRunner,
    private val maximumAttempts: Int = 3,
) : UploadIndexPublisher {
    init {
        require(maximumAttempts > 0)
    }

    override suspend fun publish(requiredFileId: String) {
        require(requiredFileId.isNotBlank())
        repeat(maximumAttempts) {
            if (requiredFileId in runner.update().publishedFileIds) return
        }
        throw IllegalStateException("Pinned cloud index did not include the uploaded file")
    }
}
