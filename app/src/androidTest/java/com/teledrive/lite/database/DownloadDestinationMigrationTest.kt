package com.teledrive.lite.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadDestinationMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TeleDriveDatabase::class.java,
    )

    @Test
    fun migrationTwoToThreeAddsNullableDownloadStateWithoutChangingExistingTasks() {
        helper.createDatabase(DATABASE_NAME, 2).apply {
            execSQL(
                "INSERT INTO transfer_tasks (id,file_id,file_name_snapshot,type,status," +
                    "completed_bytes,total_bytes,current_chunk,total_chunks,speed_bytes_per_second," +
                    "attempt,next_retry_at,error_code,work_request_id,created_at,updated_at,source_uri) " +
                    "VALUES ('task',NULL,'a.bin','UPLOAD','SUCCESS',1,1,1,1,0,0,NULL,NULL,NULL,1,1,NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            3,
            true,
            Migrations.MIGRATION_2_3,
        ).use { database ->
            database.query(
                "SELECT status,destination_uri,previous_file_status FROM transfer_tasks WHERE id='task'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("SUCCESS", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "migration-download-destination"
    }
}
