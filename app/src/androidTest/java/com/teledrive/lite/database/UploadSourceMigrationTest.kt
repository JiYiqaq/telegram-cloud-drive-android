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
class UploadSourceMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TeleDriveDatabase::class.java,
    )

    @Test
    fun migrationOneToTwoAddsUploadResumeMetadataWithoutDamagingExistingRows() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                "INSERT INTO transfer_tasks (" +
                    "id,file_id,file_name_snapshot,type,status,completed_bytes,total_bytes," +
                    "current_chunk,total_chunks,speed_bytes_per_second,attempt,next_retry_at," +
                    "error_code,work_request_id,created_at,updated_at" +
                    ") VALUES ('task',NULL,'a.bin','UPLOAD','QUEUED',0,1,0,1,0,0,NULL,NULL,NULL,1,1)",
            )
            execSQL(
                "INSERT INTO folders (id,name,parent_id,created_at,updated_at) " +
                    "VALUES ('root','root',NULL,1,1)",
            )
            execSQL(
                "INSERT INTO files (id,name,mime_type,size_bytes,created_at,modified_at," +
                    "uploaded_at,parent_folder_id,sha256,encryption_format_version,chunk_size_bytes," +
                    "chunk_count,wrapped_data_key,status,is_cloud_indexed) VALUES " +
                    "('file','a.bin','application/octet-stream',1,1,1,1,'root'," +
                    "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',1,1,1," +
                    "X'00','AVAILABLE',1)",
            )
            execSQL(
                "INSERT INTO chunks (id,file_id,part_index,message_id,telegram_file_id,nonce," +
                    "encrypted_size_bytes,upload_status) VALUES " +
                    "('chunk','file',0,1,'remote',X'000000000000000000000000',35,'UPLOADED')",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            Migrations.MIGRATION_1_2,
        ).use { database ->
            database.query("SELECT id, source_uri FROM transfer_tasks WHERE id = 'task'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("task", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
            database.query("SELECT plaintext_sha256 FROM chunks WHERE id = 'chunk'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "migration-upload-source"
    }
}
