package com.teledrive.lite.repository

import androidx.room.withTransaction
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.database.TransferTaskEntity
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TransferRepositoryFailure(val chineseMessage: String) {
    FILE_NOT_FOUND("文件不存在，无法创建传输任务。"),
    TASK_NOT_FOUND("传输任务不存在。"),
    INVALID_STATE("传输任务当前状态不允许执行该操作。"),
    INVALID_PROGRESS("传输进度无效，已保留上一份有效进度。"),
    RETRY_NOT_READY("尚未到计划重试时间。"),
}

class TransferRepositoryException(
    val failure: TransferRepositoryFailure,
) : IllegalStateException(failure.chineseMessage)

class TransferRepository(
    private val database: TeleDriveDatabase,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val taskDao = database.transferTaskDao()
    private val fileDao = database.fileDao()
    private val mutationMutex = Mutex()

    fun observeAll(): Flow<List<TransferTaskEntity>> = taskDao.observeAll()

    fun observeActive(): Flow<List<TransferTaskEntity>> = taskDao.observeActive()

    suspend fun dismissTerminal(taskId: String) = mutationMutex.withLock {
        database.withTransaction {
            val task = taskDao.getById(taskId)
                ?: fail(TransferRepositoryFailure.TASK_NOT_FOUND)
            if (!TransferHistoryPolicy.canDismiss(task)) {
                fail(TransferRepositoryFailure.INVALID_STATE)
            }
            if (taskDao.deleteTerminalById(taskId) != 1) {
                fail(TransferRepositoryFailure.TASK_NOT_FOUND)
            }
        }
    }

    suspend fun clearTerminalHistory(): Int = mutationMutex.withLock {
        database.withTransaction {
            taskDao.deleteTerminalHistory()
        }
    }

    suspend fun enqueue(
        fileId: String,
        type: TransferType,
        workRequestId: String?,
    ): TransferTaskEntity = mutationMutex.withLock {
        database.withTransaction {
            val file = fileDao.getById(fileId) ?: fail(TransferRepositoryFailure.FILE_NOT_FOUND)
            if (!TransferFileEligibility.canExecute(type, file.status, file.isCloudIndexed)) {
                fail(TransferRepositoryFailure.INVALID_STATE)
            }
            val expectedChunkCount = FileChunkLayout.expectedChunkCount(
                file.sizeBytes,
                file.chunkSizeBytes,
            )
            if (expectedChunkCount == null || file.chunkCount != expectedChunkCount) {
                fail(TransferRepositoryFailure.INVALID_PROGRESS)
            }
            val now = clock()
            val task = try {
                TransferTaskEntity(
                    id = idGenerator(),
                    fileId = file.id,
                    fileNameSnapshot = file.name,
                    type = type,
                    status = TransferStatus.QUEUED,
                    completedBytes = 0,
                    totalBytes = file.sizeBytes,
                    currentChunk = 0,
                    totalChunks = expectedChunkCount,
                    speedBytesPerSecond = 0,
                    attempt = 0,
                    nextRetryAtEpochMillis = null,
                    errorCode = null,
                    workRequestId = workRequestId,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
            } catch (_: IllegalArgumentException) {
                fail(TransferRepositoryFailure.INVALID_PROGRESS)
            }
            taskDao.upsert(task)
            task
        }
    }

    suspend fun transition(
        taskId: String,
        next: TransferStatus,
        errorCode: String? = null,
    ) = mutationMutex.withLock {
        database.withTransaction {
            val task = taskDao.getById(taskId) ?: fail(TransferRepositoryFailure.TASK_NOT_FOUND)
            if (!TransferStateMachine.canTransition(task.status, next)) {
                fail(TransferRepositoryFailure.INVALID_STATE)
            }
            if (next in EXECUTABLE_STATES) requireExecutableFile(task)
            if (next == TransferStatus.SUCCESS && !task.isComplete()) {
                fail(TransferRepositoryFailure.INVALID_PROGRESS)
            }
            if (next == TransferStatus.FAILED && errorCode.isNullOrBlank()) {
                fail(TransferRepositoryFailure.INVALID_STATE)
            }
            val persistedError = if (next == TransferStatus.FAILED) errorCode else null
            val speed = if (next == TransferStatus.RUNNING) task.speedBytesPerSecond else 0
            if (taskDao.updateStatus(taskId, next, persistedError, speed, clock()) != 1) {
                fail(TransferRepositoryFailure.TASK_NOT_FOUND)
            }
        }
    }

    suspend fun scheduleRetry(
        taskId: String,
        retryAtEpochMillis: Long,
        errorCode: String,
    ) = mutationMutex.withLock {
        database.withTransaction {
            val task = taskDao.getById(taskId) ?: fail(TransferRepositoryFailure.TASK_NOT_FOUND)
            val now = clock()
            if (
                !TransferStateMachine.canScheduleRetry(task.status) ||
                retryAtEpochMillis <= now ||
                errorCode.isBlank()
            ) {
                fail(TransferRepositoryFailure.INVALID_STATE)
            }
            requireExecutableFile(task)
            if (taskDao.scheduleRetry(taskId, retryAtEpochMillis, errorCode, now) != 1) {
                fail(TransferRepositoryFailure.TASK_NOT_FOUND)
            }
        }
    }

    suspend fun updateProgress(
        taskId: String,
        completedBytes: Long,
        currentChunk: Int,
        speedBytesPerSecond: Long,
    ) = mutationMutex.withLock {
        database.withTransaction {
            val task = taskDao.getById(taskId) ?: fail(TransferRepositoryFailure.TASK_NOT_FOUND)
            if (task.status != TransferStatus.RUNNING) {
                fail(TransferRepositoryFailure.INVALID_STATE)
            }
            try {
                TransferProgressValidator.requireValid(
                    previousBytes = task.completedBytes,
                    nextBytes = completedBytes,
                    totalBytes = task.totalBytes,
                    previousChunk = task.currentChunk,
                    nextChunk = currentChunk,
                    totalChunks = task.totalChunks,
                    speedBytesPerSecond = speedBytesPerSecond,
                )
            } catch (_: IllegalArgumentException) {
                fail(TransferRepositoryFailure.INVALID_PROGRESS)
            }
            if (
                taskDao.updateProgress(
                    taskId,
                    completedBytes,
                    currentChunk,
                    speedBytesPerSecond,
                    clock(),
                ) != 1
            ) {
                fail(TransferRepositoryFailure.TASK_NOT_FOUND)
            }
        }
    }

    suspend fun retry(taskId: String) = mutationMutex.withLock {
        database.withTransaction {
            val task = taskDao.getById(taskId) ?: fail(TransferRepositoryFailure.TASK_NOT_FOUND)
            if (!TransferStateMachine.canRetry(task.status)) {
                fail(TransferRepositoryFailure.INVALID_STATE)
            }
            requireExecutableFile(task)
            val now = clock()
            if (
                task.status == TransferStatus.WAITING_FOR_RETRY &&
                (task.nextRetryAtEpochMillis == null || now < task.nextRetryAtEpochMillis)
            ) {
                fail(TransferRepositoryFailure.RETRY_NOT_READY)
            }
            if (taskDao.retry(taskId, now) != 1) {
                fail(TransferRepositoryFailure.TASK_NOT_FOUND)
            }
        }
    }

    private suspend fun requireExecutableFile(task: TransferTaskEntity) {
        val fileId = task.fileId ?: fail(TransferRepositoryFailure.FILE_NOT_FOUND)
        val file = fileDao.getById(fileId) ?: fail(TransferRepositoryFailure.FILE_NOT_FOUND)
        if (!TransferFileEligibility.canExecute(task.type, file.status, file.isCloudIndexed)) {
            fail(TransferRepositoryFailure.INVALID_STATE)
        }
    }

    private fun TransferTaskEntity.isComplete(): Boolean =
        completedBytes == totalBytes && currentChunk == totalChunks

    private fun fail(failure: TransferRepositoryFailure): Nothing =
        throw TransferRepositoryException(failure)

    private companion object {
        val EXECUTABLE_STATES = setOf(TransferStatus.QUEUED, TransferStatus.RUNNING)
    }
}
