package com.teledrive.lite.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.PendingOperationStatus
import com.teledrive.lite.model.PendingOperationType
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.CASCADE,
            deferred = true,
        ),
    ],
    indices = [
        Index(value = ["parent_id"]),
        Index(value = ["parent_id", "name"], unique = true),
        Index(value = ["parent_id", "updated_at"]),
    ],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "files",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_folder_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["parent_folder_id"]),
        Index(value = ["parent_folder_id", "name"], unique = true),
        Index(value = ["parent_folder_id", "modified_at"]),
        Index(value = ["parent_folder_id", "size_bytes"]),
        Index(value = ["status"]),
        Index(value = ["is_cloud_indexed", "status"]),
    ],
)
data class FileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "modified_at") val modifiedAtEpochMillis: Long,
    @ColumnInfo(name = "uploaded_at") val uploadedAtEpochMillis: Long?,
    @ColumnInfo(name = "parent_folder_id") val parentFolderId: String,
    val sha256: String?,
    @ColumnInfo(name = "encryption_format_version") val encryptionFormatVersion: Int,
    @ColumnInfo(name = "chunk_size_bytes") val chunkSizeBytes: Int,
    @ColumnInfo(name = "chunk_count") val chunkCount: Int,
    @ColumnInfo(name = "wrapped_data_key", typeAffinity = ColumnInfo.BLOB)
    val wrappedDataKey: ByteArray?,
    val status: FileStatus,
    @ColumnInfo(name = "is_cloud_indexed") val isCloudIndexed: Boolean = false,
) {
    init {
        require(sizeBytes >= 0)
        require(chunkSizeBytes > 0)
        require(chunkCount >= 0)
        require(encryptionFormatVersion > 0)
    }
}

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["file_id"]),
        Index(value = ["file_id", "part_index"], unique = true),
        Index(value = ["upload_status"]),
    ],
)
data class ChunkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "file_id") val fileId: String,
    @ColumnInfo(name = "part_index") val partIndex: Int,
    @ColumnInfo(name = "message_id") val messageId: Long?,
    @ColumnInfo(name = "telegram_file_id") val telegramFileId: String?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val nonce: ByteArray?,
    @ColumnInfo(name = "encrypted_size_bytes") val encryptedSizeBytes: Long,
    @ColumnInfo(name = "upload_status") val uploadStatus: ChunkUploadStatus,
) {
    init {
        require(partIndex >= 0)
        require(encryptedSizeBytes >= 0)
    }
}

@Entity(
    tableName = "transfer_tasks",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["file_id"]),
        Index(value = ["status", "updated_at"]),
    ],
)
data class TransferTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "file_id") val fileId: String?,
    @ColumnInfo(name = "file_name_snapshot") val fileNameSnapshot: String,
    val type: TransferType,
    val status: TransferStatus,
    @ColumnInfo(name = "completed_bytes") val completedBytes: Long,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "current_chunk") val currentChunk: Int,
    @ColumnInfo(name = "total_chunks") val totalChunks: Int,
    @ColumnInfo(name = "speed_bytes_per_second") val speedBytesPerSecond: Long,
    val attempt: Int,
    @ColumnInfo(name = "next_retry_at") val nextRetryAtEpochMillis: Long?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "work_request_id") val workRequestId: String?,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
) {
    init {
        require(completedBytes >= 0 && totalBytes >= 0 && completedBytes <= totalBytes)
        require(currentChunk >= 0 && totalChunks >= 0 && currentChunk <= totalChunks)
        require(speedBytesPerSecond >= 0)
        require(attempt >= 0)
    }
}

/** Non-sensitive preferences only. Secrets are stored through the Keystore-backed settings layer. */
@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "index_state")
data class IndexStateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "format_version") val formatVersion: Int,
    val revision: Long,
    @ColumnInfo(name = "root_folder_id") val rootFolderId: String,
    @ColumnInfo(name = "current_message_id") val currentIndexMessageId: Long?,
    @ColumnInfo(name = "previous_message_id") val previousIndexMessageId: Long?,
    @ColumnInfo(name = "current_file_id") val currentIndexFileId: String?,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAtEpochMillis: Long?,
    @ColumnInfo(name = "sync_status") val syncStatus: IndexSyncStatus,
) {
    init {
        require(formatVersion > 0)
        require(revision >= 0)
    }

    companion object {
        const val SINGLETON_ID: String = "cloud-index"
    }
}

@Entity(
    tableName = "pending_operations",
    indices = [
        Index(value = ["status", "created_at"]),
        Index(value = ["target_id"]),
    ],
)
data class PendingOperationEntity(
    @PrimaryKey val id: String,
    val type: PendingOperationType,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String?,
    @ColumnInfo(name = "remaining_message_ids") val remainingMessageIdsJson: String?,
    @ColumnInfo(name = "base_revision") val baseRevision: Long?,
    @ColumnInfo(name = "candidate_revision") val candidateRevision: Long?,
    @ColumnInfo(name = "index_confirmed_at") val indexConfirmedAtEpochMillis: Long?,
    val status: PendingOperationStatus,
    val attempt: Int,
    @ColumnInfo(name = "next_retry_at") val nextRetryAtEpochMillis: Long?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
) {
    init {
        require(attempt >= 0)
        require(baseRevision == null || baseRevision >= 0)
        require(candidateRevision == null || candidateRevision >= 0)
    }
}
