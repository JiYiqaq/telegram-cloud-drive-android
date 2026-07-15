package com.teledrive.lite.model

enum class FileStatus {
    PENDING,
    ENCRYPTING,
    UPLOADING,
    AVAILABLE,
    DOWNLOADING,
    FAILED,
    DELETING,
    PARTIALLY_DELETED,
    CORRUPTED,
}

enum class TransferStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_FOR_NETWORK,
    WAITING_FOR_RETRY,
    SUCCESS,
    FAILED,
    CANCELED,
}

enum class ChunkUploadStatus {
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED,
    DELETING,
    DELETED,
}

enum class TransferType {
    UPLOAD,
    DOWNLOAD,
}

enum class PendingOperationType {
    CREATE_FOLDER,
    RENAME,
    MOVE,
    DELETE,
    INDEX_UPDATE,
}

enum class PendingOperationStatus {
    PENDING,
    RUNNING,
    FAILED,
}

enum class IndexSyncStatus {
    NOT_INITIALIZED,
    SYNCED,
    DIRTY,
    SYNCING,
    FAILED,
}

enum class SortMode {
    NAME,
    SIZE,
    UPDATED_AT,
}

enum class SortDirection {
    ASCENDING,
    DESCENDING,
}
