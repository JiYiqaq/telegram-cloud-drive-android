package com.teledrive.lite.database

import androidx.room.TypeConverter
import com.teledrive.lite.model.ChunkUploadStatus
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.PendingOperationStatus
import com.teledrive.lite.model.PendingOperationType
import com.teledrive.lite.model.TransferStatus
import com.teledrive.lite.model.TransferType

class DatabaseConverters {
    @TypeConverter
    fun fileStatusToString(value: FileStatus): String = value.name

    @TypeConverter
    fun stringToFileStatus(value: String): FileStatus = FileStatus.valueOf(value)

    @TypeConverter
    fun chunkStatusToString(value: ChunkUploadStatus): String = value.name

    @TypeConverter
    fun stringToChunkStatus(value: String): ChunkUploadStatus = ChunkUploadStatus.valueOf(value)

    @TypeConverter
    fun transferStatusToString(value: TransferStatus): String = value.name

    @TypeConverter
    fun stringToTransferStatus(value: String): TransferStatus = TransferStatus.valueOf(value)

    @TypeConverter
    fun transferTypeToString(value: TransferType): String = value.name

    @TypeConverter
    fun stringToTransferType(value: String): TransferType = TransferType.valueOf(value)

    @TypeConverter
    fun indexStatusToString(value: IndexSyncStatus): String = value.name

    @TypeConverter
    fun stringToIndexStatus(value: String): IndexSyncStatus = IndexSyncStatus.valueOf(value)

    @TypeConverter
    fun pendingTypeToString(value: PendingOperationType): String = value.name

    @TypeConverter
    fun stringToPendingType(value: String): PendingOperationType = PendingOperationType.valueOf(value)

    @TypeConverter
    fun pendingStatusToString(value: PendingOperationStatus): String = value.name

    @TypeConverter
    fun stringToPendingStatus(value: String): PendingOperationStatus = PendingOperationStatus.valueOf(value)
}
