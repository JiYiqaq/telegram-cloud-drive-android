package com.teledrive.lite.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CryptoEngineTest {
    private val engine = CryptoEngine()

    @Test
    fun aes256GcmChunkRoundTripsWithVersionedEnvelope() {
        val key = engine.generateDataKey()
        val plaintext = "encrypted file content".encodeToByteArray()
        val aad = "file-1:chunk-0".encodeToByteArray()

        val encrypted = engine.encryptChunk(key, plaintext, aad)

        assertEquals(CryptoEngine.DATA_KEY_BYTES, key.size)
        assertEquals(plaintext.size + CryptoEngine.ENVELOPE_OVERHEAD_BYTES, encrypted.size)
        assertFalse(encrypted.containsSlice(plaintext))
        assertArrayEquals(plaintext, engine.decryptChunk(key, encrypted, aad))
    }

    @Test
    fun eachFileKeyAndChunkNonceAreUnique() {
        val firstKey = engine.generateDataKey()
        val secondKey = engine.generateDataKey()
        val plaintext = ByteArray(64) { 3 }
        val aad = byteArrayOf(9)

        val firstChunk = engine.encryptChunk(firstKey, plaintext, aad)
        val secondChunk = engine.encryptChunk(firstKey, plaintext, aad)

        assertFalse(firstKey.contentEquals(secondKey))
        assertFalse(firstChunk.contentEquals(secondChunk))
    }

    @Test
    fun wrongKeyWrongAadAndTamperingFailAuthentication() {
        val key = engine.generateDataKey()
        val wrongKey = engine.generateDataKey()
        val aad = "file-1:chunk-0".encodeToByteArray()
        val encrypted = engine.encryptChunk(key, "secret".encodeToByteArray(), aad)

        assertThrows(CryptoAuthenticationException::class.java) {
            engine.decryptChunk(wrongKey, encrypted, aad)
        }
        assertThrows(CryptoAuthenticationException::class.java) {
            engine.decryptChunk(key, encrypted, "file-1:chunk-1".encodeToByteArray())
        }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(CryptoAuthenticationException::class.java) {
            engine.decryptChunk(key, encrypted, aad)
        }
    }

    @Test
    fun unsupportedEnvelopeVersionFailsWithoutDecrypting() {
        val key = engine.generateDataKey()
        val encrypted = engine.encryptChunk(key, byteArrayOf(1), byteArrayOf(2))
        encrypted[CryptoEngine.VERSION_OFFSET] = 99

        assertThrows(CryptoFormatException::class.java) {
            engine.decryptChunk(key, encrypted, byteArrayOf(2))
        }
    }

    @Test
    fun fileDataKeyWrapsWithMasterKeyAndFileIdentityBinding() {
        val wrapping = KeyWrapping(engine)
        val masterKey = engine.generateDataKey()
        val fileKey = engine.generateDataKey()

        val wrapped = wrapping.wrap("file-1", masterKey, fileKey)

        assertFalse(wrapped.containsSlice(fileKey))
        assertArrayEquals(fileKey, wrapping.unwrap("file-1", masterKey, wrapped))
        assertThrows(CryptoAuthenticationException::class.java) {
            wrapping.unwrap("file-2", masterKey, wrapped)
        }
        assertThrows(CryptoAuthenticationException::class.java) {
            wrapping.unwrap("file-1", engine.generateDataKey(), wrapped)
        }
    }

    private fun ByteArray.containsSlice(candidate: ByteArray): Boolean {
        if (candidate.isEmpty()) return true
        return indices.any { start ->
            start + candidate.size <= size &&
                copyOfRange(start, start + candidate.size).contentEquals(candidate)
        }
    }
}
