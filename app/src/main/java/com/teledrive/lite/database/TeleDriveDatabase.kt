package com.teledrive.lite.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        FolderEntity::class,
        FileEntity::class,
        ChunkEntity::class,
        TransferTaskEntity::class,
        AppConfigEntity::class,
        IndexStateEntity::class,
        PendingOperationEntity::class,
    ],
    version = TeleDriveDatabase.DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class TeleDriveDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao

    abstract fun fileDao(): FileDao

    abstract fun chunkDao(): ChunkDao

    abstract fun transferTaskDao(): TransferTaskDao

    abstract fun appConfigDao(): AppConfigDao

    abstract fun indexStateDao(): IndexStateDao

    abstract fun pendingOperationDao(): PendingOperationDao

    companion object {
        const val DATABASE_VERSION: Int = 1
        private const val DATABASE_NAME = "teledrive_cache.db"

        fun create(context: Context): TeleDriveDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                TeleDriveDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
