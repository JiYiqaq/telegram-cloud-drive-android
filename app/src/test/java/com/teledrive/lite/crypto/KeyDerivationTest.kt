package com.teledrive.lite.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyDerivationTest {
    @Test
    fun pbkdf2Sha256MatchesKnownVector() {
        val parameters = KeyDerivationParameters.pbkdf2(
            salt = "salt".encodeToByteArray(),
            iterations = 1,
            keyLengthBytes = 32,
        )

        val derived = KeyDerivation.derive("password".toCharArray(), parameters)

        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            derived.toHex(),
        )
    }

    @Test
    fun derivationIsDeterministicAndPasswordSensitive() {
        val parameters = KeyDerivationParameters.pbkdf2(
            salt = ByteArray(16) { it.toByte() },
            iterations = 2_000,
        )

        val first = KeyDerivation.derive("correct password".toCharArray(), parameters)
        val second = KeyDerivation.derive("correct password".toCharArray(), parameters)
        val wrong = KeyDerivation.derive("wrong password".toCharArray(), parameters)

        assertArrayEquals(first, second)
        assertFalse(first.contentEquals(wrong))
        assertEquals(KeyDerivation.MASTER_KEY_BYTES, first.size)
    }

    @Test
    fun productionParametersUseRandomSaltAndHighWorkFactor() {
        val first = KeyDerivation.newParameters()
        val second = KeyDerivation.newParameters()

        assertEquals(KeyDerivation.ALGORITHM, first.algorithm)
        assertTrue(first.iterations >= 300_000)
        assertTrue(first.salt.size >= 16)
        assertFalse(first.salt.contentEquals(second.salt))
    }

    @Test
    fun parameterSaltIsDefensivelyCopied() {
        val original = ByteArray(16) { (it + 1).toByte() }
        val parameters = KeyDerivationParameters.pbkdf2(original, iterations = 2_000)
        original.fill(0)
        val exposed = parameters.salt
        exposed.fill(0)

        assertFalse(parameters.salt.all { it == 0.toByte() })
    }

    @Test
    fun derivationDoesNotMutateCallerPassword() {
        val password = "correct password".toCharArray()
        val snapshot = password.copyOf()
        val parameters = KeyDerivationParameters.pbkdf2(ByteArray(16), iterations = 2_000)

        KeyDerivation.derive(password, parameters)

        assertArrayEquals(snapshot, password)
    }

    @Test
    fun attackerControlledParametersHaveResourceBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivationParameters.pbkdf2(
                salt = ByteArray(16),
                iterations = 2_000,
                keyLengthBytes = 33,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivationParameters.pbkdf2(
                salt = ByteArray(65),
                iterations = 2_000,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivationParameters.pbkdf2(
                salt = ByteArray(16),
                iterations = 2_000_001,
            )
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
