package com.teledrive.lite.settings

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AesGcmSecretCipherTest {
    @Test
    fun roundTripUsesVersionedEnvelopeWithoutPlaintext() {
        val provider = InMemoryKeyProvider()
        val cipher = AesGcmSecretCipher(provider)
        val plaintext = "sensitive local configuration".encodeToByteArray()
        val aad = "config-field".encodeToByteArray()

        val encrypted = cipher.encrypt(plaintext, aad)

        assertArrayEquals(plaintext, cipher.decrypt(encrypted, aad))
        assertFalse(encrypted.containsSlice(plaintext))
        assertTrue(encrypted.size > plaintext.size)
    }

    @Test
    fun samePlaintextUsesUniqueNonceEveryTime() {
        val cipher = AesGcmSecretCipher(InMemoryKeyProvider())
        val plaintext = ByteArray(32) { 7 }
        val aad = "session-key".encodeToByteArray()

        val first = cipher.encrypt(plaintext, aad)
        val second = cipher.encrypt(plaintext, aad)

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun wrongAssociatedDataAndTamperingAreRejected() {
        val cipher = AesGcmSecretCipher(InMemoryKeyProvider())
        val encrypted = cipher.encrypt(
            plaintext = "secret".encodeToByteArray(),
            associatedData = "token".encodeToByteArray(),
        )

        assertThrows(Exception::class.java) {
            cipher.decrypt(encrypted, "chat-id".encodeToByteArray())
        }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            cipher.decrypt(encrypted, "token".encodeToByteArray())
        }
    }

    @Test
    fun unknownEnvelopeVersionIsRejectedBeforeDecryption() {
        val cipher = AesGcmSecretCipher(InMemoryKeyProvider())
        val encrypted = cipher.encrypt(byteArrayOf(1), byteArrayOf(2))
        encrypted[AesGcmSecretCipher.VERSION_OFFSET] = 99

        assertThrows(IllegalArgumentException::class.java) {
            cipher.decrypt(encrypted, byteArrayOf(2))
        }
    }

    @Test
    fun deleteKeyDelegatesToProvider() {
        val provider = InMemoryKeyProvider()
        val cipher = AesGcmSecretCipher(provider)

        cipher.deleteKey()

        assertTrue(provider.deleted)
    }

    private class InMemoryKeyProvider : SecretKeyProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES").run {
            init(256)
            generateKey()
        }
        var deleted: Boolean = false
            private set

        override fun getOrCreate(): SecretKey = key

        override fun delete() {
            deleted = true
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
