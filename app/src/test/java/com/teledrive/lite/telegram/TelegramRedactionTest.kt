package com.teledrive.lite.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramRedactionTest {
    @Test
    fun redactsTokenAndTelegramDownloadUrlFromArbitraryText() {
        val token = "YOUR_BOT_TOKEN"
        val text = "failed $token at https://api.telegram.org/file/bot$token/documents/private.bin"

        val redacted = SecretRedactor.redact(text, token)

        assertFalse(redacted.contains(token))
        assertFalse(redacted.contains("api.telegram.org/file"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun cloudLimitsKeepDefaultChunkBelowDownloadBoundary() {
        assertEquals(19L * 1024L * 1024L, TelegramCloudLimits.DEFAULT_CHUNK_SIZE_BYTES)
        assertEquals(20_000_000L, TelegramCloudLimits.MAX_DOWNLOADABLE_ENCRYPTED_BYTES)
        assertTrue(
            TelegramCloudLimits.isEncryptedChunkSizeSafe(
                TelegramCloudLimits.DEFAULT_CHUNK_SIZE_BYTES + TelegramCloudLimits.GCM_TAG_BYTES,
            ),
        )
        assertFalse(
            TelegramCloudLimits.isEncryptedChunkSizeSafe(
                TelegramCloudLimits.MAX_DOWNLOADABLE_ENCRYPTED_BYTES,
            ),
        )
    }
}
