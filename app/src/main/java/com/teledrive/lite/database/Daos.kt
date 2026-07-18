package com.teledrive.lite.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.PendingOperationStatus
import com.teledrive.lite.model.TransferStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE, id")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE parent_id = :parentId ORDER BY name COLLATE NOCASE, id")
    fun observeChildren(parentId: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE instr(lower(name), lower(:query)) > 0 ORDER BY name COLLATE NOCASE")
    fun search(query: String): Flow<List<FolderEntity>>

    @Query("SELECT COUNT(*) FROM folders WHERE parent_id = :parentId")
    suspend fun countChildren(parentId: String): Int

    @Query("SELECT COUNT(*) FROM folders WHERE parent_id = :parentId AND name = :name COLLATE NOCASE")
    suspend fun countName(parentId: String, name: String): Int

    @Query(
        "SELECT COUNT(*) FROM folders " +
            "WHERE parent_id = :parentId AND name = :name COLLATE NOCASE AND id != :excludedId",
    )
    suspend fun countNameExcluding(parentId: String, name: String, excludedId: String): Int

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Upsert
    suspend fun upsertAll(folders: List<FolderEntity>)

    @Query("UPDATE folders SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long): Int

    @Query("UPDATE folders SET parent_id = :parentId, updated_at = :updatedAt WHERE id = :id")
    suspend fun move(id: String, parentId: String, updatedAt: Long): Int

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM folders")
    suspend fun deleteAll()
}

@Dao
interface FileDao {
    @Query("SELECT * FROM files ORDER BY name COLLATE NOCASE, id")
    suspend fun getAll(): List<FileEntity>

    @Query("SELECT * FROM files WHERE parent_folder_id = :folderId ORDER BY name COLLATE NOCASE, id")
    fun observeInFolder(folderId: String): Flow<List<FileEntity>>

    @Query("SELECT * FROM files ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun getById(id: String): FileEntity?

    @Query("SELECT * FROM files WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<FileEntity>

    @Query("SELECT * FROM files WHERE instr(lower(name), lower(:query)) > 0 ORDER BY name COLLATE NOCASE")
    fun search(query: String): Flow<List<FileEntity>>

    @Query("SELECT COUNT(*) FROM files WHERE parent_folder_id = :folderId")
    suspend fun countInFolder(folderId: String): Int

    @Query("SELECT COUNT(*) FROM files WHERE parent_folder_id = :folderId AND name = :name COLLATE NOCASE")
    suspend fun countName(folderId: String, name: String): Int

    @Query(
        "SELECT COUNT(*) FROM files " +
            "WHERE parent_folder_id = :folderId AND name = :name COLLATE NOCASE AND id NOT IN (:excludedIds)",
    )
    suspend fun countNameExcluding(folderId: String, name: String, excludedIds: List<String>): Int

    @Upsert
    suspend fun upsert(file: FileEntity)

    @Upsert
    suspend fun upsertAll(files: List<FileEntity>)

    @Query("UPDATE files SET name = :name, modified_at = :modifiedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, modifiedAt: Long): Int

    @Query("UPDATE files SET parent_folder_id = :folderId, modified_at = :modifiedAt WHERE id IN (:ids)")
    suspend fun moveAll(ids: List<String>, folderId: String, modifiedAt: Long): Int

    @Query("UPDATE files SET status = :status, modified_at = :modifiedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: FileStatus, modifiedAt: Long): Int

    @Query(
        "UPDATE files SET sha256 = :sha256, wrapped_data_key = :wrappedDataKey, " +
            "status = :status WHERE id = :id AND is_cloud_indexed = 0",
    )
    suspend fun persistUploadSecurity(
        id: String,
        sha256: String,
        wrappedDataKey: ByteArray,
        status: FileStatus,
    ): Int

    @Query(
        "UPDATE files SET status = :status, is_cloud_indexed = 1, uploaded_at = :uploadedAt " +
            "WHERE id = :id AND is_cloud_indexed = 0",
    )
    suspend fun finalizeUpload(
        id: String,
        status: FileStatus,
        uploadedAt: Long,
    ): Int

    @Query("DELETE FROM files WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM files")
    suspend fun deleteAll()
}

@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks ORDER BY file_id, part_index, id")
    suspend fun getAll(): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE file_id = :fileId ORDER BY part_index")
    fun observeForFile(fileId: String): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks WHERE file_id = :fileId ORDER BY part_index")
    suspend fun getForFile(fileId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE id = :id")
    suspend fun getById(id: String): ChunkEntity?

    @Upsert
    suspend fun upsert(chunk: ChunkEntity)

    @Upsert
    suspend fun upsertAll(chunks: List<ChunkEntity>)

    @Query("UPDATE chunks SET upload_status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: ChunkUploadStatus): Int

    @Query("DELETE FROM chunks WHERE file_id = :fileId")
    suspend fun deleteForFile(fileId: String): Int

    @Query("DELETE FROM chunks")
    suspend fun deleteAll()
}

@Dao
interface TransferTaskDao {
    @Query("SELECT * FROM transfer_tasks ORDER BY updated_at DESC")
    suspend fun getAll(): List<TransferTaskEntity>

    @Query("SELECT * FROM transfer_tasks ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<TransferTaskEntity>>

    @Query(
        "SELECT * FROM transfer_tasks WHERE file_id IS NOT NULL " +
            "AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELED') ORDER BY created_at",
    )
    fun observeActive(): Flow<List<TransferTaskEntity>>

    @Query(
        "SELECT * FROM transfer_tasks WHERE file_id = :fileId " +
            "AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELED') ORDER BY created_at",
    )
    suspend fun getActiveForFile(fileId: String): List<TransferTaskEntity>

    @Query(
        "SELECT COUNT(*) FROM transfer_tasks WHERE file_id = :fileId " +
            "AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELED')",
    )
    suspend fun countActiveForFile(fileId: String): Int

    @Query(
        "SELECT COUNT(*) FROM transfer_tasks WHERE file_id IS NOT NULL " +
            "AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELED')",
    )
    suspend fun countActive(): Int

    @Query("SELECT * FROM transfer_tasks WHERE id = :id")
    suspend fun getById(id: String): TransferTaskEntity?

    @Query(
        "SELECT id FROM transfer_tasks WHERE type = 'UPLOAD' " +
            "AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELED') " +
            "AND source_uri IS NOT NULL ORDER BY created_at",
    )
    suspend fun getRecoverableUploadTaskIds(): List<String>

    @Upsert
    suspend fun upsert(task: TransferTaskEntity)

    @Query(
        "UPDATE transfer_tasks SET status = :status, error_code = :errorCode, " +
            "next_retry_at = NULL, speed_bytes_per_second = :speedBytesPerSecond, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateStatus(
        id: String,
        status: TransferStatus,
        errorCode: String?,
        speedBytesPerSecond: Long,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE transfer_tasks SET completed_bytes = :completedBytes, current_chunk = :currentChunk, " +
            "speed_bytes_per_second = :speedBytesPerSecond, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateProgress(
        id: String,
        completedBytes: Long,
        currentChunk: Int,
        speedBytesPerSecond: Long,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE transfer_tasks SET status = 'RUNNING', error_code = NULL, " +
            "speed_bytes_per_second = 0, updated_at = :updatedAt " +
            "WHERE id = :id AND type = 'UPLOAD' AND work_request_id = :workRequestId " +
            "AND status IN ('QUEUED', 'PAUSED', 'WAITING_FOR_NETWORK', " +
            "'WAITING_FOR_RETRY', 'RUNNING')",
    )
    suspend fun startPendingUploadWork(
        id: String,
        workRequestId: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE transfer_tasks SET status = :status, error_code = :errorCode, " +
            "next_retry_at = NULL, speed_bytes_per_second = 0, updated_at = :updatedAt " +
            "WHERE id = :id AND type = 'UPLOAD' AND work_request_id = :workRequestId " +
            "AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELED')",
    )
    suspend fun stopPendingUploadWork(
        id: String,
        workRequestId: String,
        status: TransferStatus,
        errorCode: String?,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE transfer_tasks SET status = 'WAITING_FOR_RETRY', next_retry_at = :retryAt, " +
            "error_code = :errorCode, speed_bytes_per_second = 0, updated_at = :updatedAt " +
            "WHERE id = :id AND type = 'UPLOAD' AND work_request_id = :workRequestId " +
            "AND status = 'RUNNING'",
    )
    suspend fun scheduleUploadRetryForWork(
        id: String,
        workRequestId: String,
        retryAt: Long,
        errorCode: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE transfer_tasks SET status = 'WAITING_FOR_RETRY', next_retry_at = :retryAt, " +
            "error_code = :errorCode, speed_bytes_per_second = 0, updated_at = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun scheduleRetry(
        id: String,
        retryAt: Long,
        errorCode: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE transfer_tasks SET status = 'QUEUED', attempt = attempt + 1, " +
            "next_retry_at = NULL, error_code = NULL, speed_bytes_per_second = 0, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun retry(id: String, updatedAt: Long): Int

    @Query(
        "UPDATE transfer_tasks SET status = 'QUEUED', attempt = attempt + 1, " +
            "work_request_id = :workRequestId, next_retry_at = NULL, error_code = NULL, " +
            "speed_bytes_per_second = 0, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun retryWithWorkRequest(
        id: String,
        workRequestId: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE transfer_tasks SET work_request_id = :workRequestId, updated_at = :updatedAt " +
            "WHERE id = :id AND type = 'UPLOAD' " +
            "AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELED')",
    )
    suspend fun replacePendingUploadWorkRequest(
        id: String,
        workRequestId: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE transfer_tasks SET status = 'WAITING_FOR_NETWORK', error_code = NULL, " +
            "speed_bytes_per_second = 0, updated_at = :updatedAt " +
            "WHERE type = 'UPLOAD' AND status = 'QUEUED'",
    )
    suspend fun markQueuedUploadsWaitingForNetwork(updatedAt: Long): Int

    @Query(
        "UPDATE transfer_tasks SET status = 'QUEUED', completed_bytes = 0, current_chunk = 0, " +
            "attempt = attempt + 1, work_request_id = :workRequestId, next_retry_at = NULL, " +
            "error_code = NULL, speed_bytes_per_second = 0, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun restartWithWorkRequest(
        id: String,
        workRequestId: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE transfer_tasks SET status = 'WAITING_FOR_NETWORK', error_code = NULL, " +
            "speed_bytes_per_second = 0, updated_at = :updatedAt " +
            "WHERE type = 'DOWNLOAD' AND status = 'QUEUED'",
    )
    suspend fun markQueuedDownloadsWaitingForNetwork(updatedAt: Long): Int

    @Query(
        "UPDATE transfer_tasks SET status = 'CANCELED', error_code = :reason, " +
            "next_retry_at = NULL, speed_bytes_per_second = 0, updated_at = :updatedAt " +
            "WHERE file_id = :fileId AND status NOT IN ('SUCCESS', 'FAILED', 'CANCELED')",
    )
    suspend fun cancelActiveForFile(fileId: String, reason: String, updatedAt: Long): Int

    @Query("DELETE FROM transfer_tasks WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query(
        "DELETE FROM transfer_tasks WHERE id = :id " +
            "AND (" +
            "status = 'SUCCESS' OR " +
            "(status IN ('FAILED', 'CANCELED') AND (type = 'DOWNLOAD' OR file_id IS NULL))" +
            ")",
    )
    suspend fun deleteTerminalById(id: String): Int

    @Query(
        "DELETE FROM transfer_tasks WHERE " +
            "status = 'SUCCESS' OR " +
            "(status IN ('FAILED', 'CANCELED') AND (type = 'DOWNLOAD' OR file_id IS NULL))",
    )
    suspend fun deleteTerminalHistory(): Int
}

@Dao
interface AppConfigDao {
    @Query("SELECT * FROM app_config WHERE `key` = :key")
    suspend fun get(key: String): AppConfigEntity?

    @Query("SELECT * FROM app_config ORDER BY `key`")
    fun observeAll(): Flow<List<AppConfigEntity>>

    @Upsert
    suspend fun upsert(config: AppConfigEntity)

    @Query("DELETE FROM app_config WHERE `key` = :key")
    suspend fun delete(key: String): Int
}

@Dao
interface IndexStateDao {
    @Query("SELECT * FROM index_state WHERE id = :id")
    fun observe(id: String): Flow<IndexStateEntity?>

    @Query("SELECT * FROM index_state WHERE id = :id")
    suspend fun get(id: String): IndexStateEntity?

    @Upsert
    suspend fun upsert(state: IndexStateEntity)

    @Query("DELETE FROM index_state")
    suspend fun deleteAll()
}

@Dao
interface PendingOperationDao {
    @Query("SELECT * FROM pending_operations ORDER BY created_at")
    fun observeAll(): Flow<List<PendingOperationEntity>>

    @Query("SELECT * FROM pending_operations WHERE status = :status ORDER BY created_at")
    fun observeByStatus(status: PendingOperationStatus): Flow<List<PendingOperationEntity>>

    @Query("SELECT * FROM pending_operations WHERE id = :id")
    suspend fun getById(id: String): PendingOperationEntity?

    @Query("SELECT * FROM pending_operations ORDER BY created_at")
    suspend fun getAll(): List<PendingOperationEntity>

    @Upsert
    suspend fun upsert(operation: PendingOperationEntity)

    @Upsert
    suspend fun upsertAll(operations: List<PendingOperationEntity>)

    @Query("DELETE FROM pending_operations WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM pending_operations")
    suspend fun deleteAll()
}
