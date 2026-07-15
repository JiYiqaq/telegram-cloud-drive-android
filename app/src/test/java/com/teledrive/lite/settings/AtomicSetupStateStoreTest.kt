package com.teledrive.lite.settings

import com.teledrive.lite.crypto.KeyDerivationParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicSetupStateStoreTest {
    @Test
    fun oneAtomicWriteIsReadableThroughAllExistingPersistenceViews() {
        val values = CountingValues()
        val configCipher = CopyCipher()
        val sessionCipher = CopyCipher()
        val store = AtomicSetupStateStore(values, configCipher, sessionCipher)
        val config = ValidatedConnectionConfig("token", -1001234567890)
        val masterKey = ByteArray(32) { (it + 1).toByte() }
        val parameters = KeyDerivationParameters.pbkdf2(ByteArray(16) { it.toByte() }, 600_000)

        store.commit(config, masterKey, parameters)

        assertEquals(1, values.putCalls)
        assertTrue(store.isComplete())
        assertEquals(config, SecureConfigStore(values, configCipher).load())
        assertArrayEquals(masterKey, SessionKeyStore(values, sessionCipher).load())
        val restoredParameters = KdfParametersStore(values).load()
        assertEquals(parameters.iterations, restoredParameters?.iterations)
        assertArrayEquals(parameters.salt, restoredParameters?.salt)
    }

    @Test
    fun encryptionFailureDoesNotTouchExistingGeneration() {
        val oldValues = mutableMapOf("old" to "stable")
        val values = CountingValues(oldValues.toMutableMap())
        val store = AtomicSetupStateStore(
            values = values,
            configCipher = FailingCipher(failOnCall = 2),
            sessionCipher = CopyCipher(),
        )

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            store.commit(
                ValidatedConnectionConfig("new-token", -1001234567890),
                ByteArray(32),
                KeyDerivationParameters.pbkdf2(ByteArray(16), 600_000),
            )
        }

        assertEquals(0, values.putCalls)
        assertEquals(oldValues, values.data)
    }

    private class CountingValues(
        val data: MutableMap<String, String> = mutableMapOf(),
    ) : StringValueStore {
        var putCalls = 0

        override fun get(key: String): String? = data[key]

        override fun put(values: Map<String, String>): Boolean {
            putCalls += 1
            data.putAll(values)
            return true
        }

        override fun remove(keys: Set<String>): Boolean {
            keys.forEach(data::remove)
            return true
        }
    }

    private open class CopyCipher : SecretCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = plaintext.copyOf()

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = ciphertext.copyOf()

        override fun deleteKey() = Unit
    }

    private class FailingCipher(
        private val failOnCall: Int,
    ) : CopyCipher() {
        private var calls = 0

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            calls += 1
            if (calls == failOnCall) throw IllegalStateException("encryption failed")
            return super.encrypt(plaintext, associatedData)
        }
    }
}
