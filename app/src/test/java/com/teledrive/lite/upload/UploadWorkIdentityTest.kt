package com.teledrive.lite.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UploadWorkIdentityTest {
    @Test
    fun `each upload task has an isolated unique work name`() {
        val first = UploadWorkIdentity.uniqueName("task-a")
        val second = UploadWorkIdentity.uniqueName("task-b")

        assertNotEquals(first, second)
        assertEquals(first, UploadWorkIdentity.uniqueName("task-a"))
    }

    @Test
    fun `legacy serial chain name remains stable for upgrade recovery`() {
        assertEquals(
            "teledrive_serial_upload_queue_v1",
            UploadWorkIdentity.LEGACY_SERIAL_QUEUE,
        )
    }
}
