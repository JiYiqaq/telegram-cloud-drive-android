package com.teledrive.lite.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Explicit migration registry. Add every future schema step here. */
object Migrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transfer_tasks ADD COLUMN source_uri TEXT")
            db.execSQL("ALTER TABLE chunks ADD COLUMN plaintext_sha256 TEXT")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transfer_tasks ADD COLUMN destination_uri TEXT")
            db.execSQL("ALTER TABLE transfer_tasks ADD COLUMN previous_file_status TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
