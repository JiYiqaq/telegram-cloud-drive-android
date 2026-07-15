package com.teledrive.lite.util

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Sha256Test {
    @Test
    fun knownDigestsMatchSha256() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.digest(byteArrayOf()),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.digest("abc".encodeToByteArray()),
        )
    }

    @Test
    fun streamDigestUsesBoundedReadsAndLeavesCallerStreamOpen() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val input = TrackingInputStream(data)

        val streamDigest = Sha256.digest(input)

        assertEquals(Sha256.digest(data), streamDigest)
        assertTrue(input.maxRequestedBytes <= Sha256.BUFFER_SIZE_BYTES)
        assertFalse(input.closed)
    }

    @Test
    fun secureEraseOverwritesMutableSensitiveBuffers() {
        val bytes = byteArrayOf(1, 2, 3)
        val chars = charArrayOf('s', 'e', 'c', 'r', 'e', 't')

        SecureErase.wipe(bytes)
        SecureErase.wipe(chars)

        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(chars.all { it == '\u0000' })
    }

    private class TrackingInputStream(data: ByteArray) : ByteArrayInputStream(data) {
        var maxRequestedBytes: Int = 0
            private set
        var closed: Boolean = false
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            maxRequestedBytes = maxOf(maxRequestedBytes, length)
            return super.read(buffer, offset, length)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
