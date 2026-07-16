package com.teledrive.lite.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudIndexEnvelopeCryptorTest {
    private val cryptor = CloudIndexEnvelopeCryptor(CryptoEngine())
    private val parameters = KeyDerivationParameters.pbkdf2(
        salt = ByteArray(16) { it.toByte() },
        iterations = 2_000,
    )

    @Test
    fun encryptedIndexRoundTripsAndExposesOnlyRequiredKdfMetadata() {
        val masterKey = ByteArray(KeyDerivation.MASTER_KEY_BYTES) { (it + 1).toByte() }
        val plaintext = "canonical cloud index with private names".encodeToByteArray()

        val envelope = cryptor.encrypt(plaintext, masterKey, parameters)
        val metadata = cryptor.inspect(envelope)

        assertFalse(envelope.containsSlice(plaintext))
        assertEquals(KeyDerivation.ALGORITHM, metadata.keyDerivation.algorithm)
        assertArrayEquals(parameters.salt, metadata.keyDerivation.salt)
        assertEquals(parameters.iterations, metadata.keyDerivation.iterations)
        assertEquals(parameters.keyLengthBytes, metadata.keyDerivation.keyLengthBytes)
        assertArrayEquals(plaintext, cryptor.decrypt(envelope, masterKey))
    }

    @Test
    fun passwordRecoveryUsesHeaderParametersAndRejectsWrongPassword() {
        val password = "correct horse battery staple".toCharArray()
        val masterKey = KeyDerivation.derive(password, parameters)
        val envelope = cryptor.encrypt("index".encodeToByteArray(), masterKey, parameters)

        assertArrayEquals(
            "index".encodeToByteArray(),
            cryptor.decryptWithPassword(envelope, password),
        )
        assertThrows(CryptoAuthenticationException::class.java) {
            cryptor.decryptWithPassword(envelope, "wrong password".toCharArray())
        }
    }

    @Test
    fun headerAndCiphertextTamperingAreAuthenticated() {
        val masterKey = ByteArray(KeyDerivation.MASTER_KEY_BYTES) { 7 }
        val envelope = cryptor.encrypt(byteArrayOf(1, 2, 3), masterKey, parameters)

        val changedSalt = envelope.copyOf().also {
            it[CloudIndexEnvelopeCryptor.SALT_OFFSET] =
                (it[CloudIndexEnvelopeCryptor.SALT_OFFSET].toInt() xor 1).toByte()
        }
        assertThrows(CryptoAuthenticationException::class.java) {
            cryptor.decrypt(changedSalt, masterKey)
        }

        val changedCiphertext = envelope.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertThrows(CryptoAuthenticationException::class.java) {
            cryptor.decrypt(changedCiphertext, masterKey)
        }
    }

    @Test
    fun malformedUnsupportedAndOversizedEnvelopesFailBeforeDecryption() {
        val masterKey = ByteArray(KeyDerivation.MASTER_KEY_BYTES) { 4 }
        val valid = cryptor.encrypt(byteArrayOf(1), masterKey, parameters)

        assertThrows(InvalidCloudIndexEnvelopeException::class.java) {
            cryptor.inspect(byteArrayOf(1, 2, 3))
        }
        assertThrows(UnsupportedCloudIndexEnvelopeVersionException::class.java) {
            cryptor.inspect(valid.copyOf().also {
                it[CloudIndexEnvelopeCryptor.VERSION_OFFSET] = 99
            })
        }
        assertThrows(UnsupportedCloudIndexKdfException::class.java) {
            cryptor.inspect(valid.copyOf().also {
                it[CloudIndexEnvelopeCryptor.KDF_OFFSET] = 99
            })
        }
        assertThrows(InvalidCloudIndexEnvelopeException::class.java) {
            cryptor.inspect(ByteArray(CloudIndexEnvelopeCryptor.MAX_ENVELOPE_BYTES + 1))
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
