package com.teledrive.lite.settings

import com.teledrive.lite.crypto.KeyDerivationParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class KdfParametersStoreTest {
    @Test
    fun roundTripsVersionedPbkdf2ParametersAndClearsThem() {
        val values = InMemoryValues()
        val store = KdfParametersStore(values)
        val parameters = KeyDerivationParameters.pbkdf2(
            salt = ByteArray(16) { (it + 1).toByte() },
            iterations = 600_000,
        )

        store.save(parameters)
        val restored = store.load()

        assertEquals(parameters.algorithm, restored?.algorithm)
        assertEquals(parameters.iterations, restored?.iterations)
        assertEquals(parameters.keyLengthBytes, restored?.keyLengthBytes)
        assertArrayEquals(parameters.salt, restored?.salt)

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun partialOrAttackerSizedParametersAreRejectedAsCorrupted() {
        val partial = InMemoryValues(mutableMapOf(KdfParametersStore.ALGORITHM_KEY to "PBKDF2-HMAC-SHA256"))
        assertThrows(SecureStorageException::class.java) { KdfParametersStore(partial).load() }

        val oversized = InMemoryValues()
        KdfParametersStore(oversized).save(
            KeyDerivationParameters.pbkdf2(ByteArray(16), 600_000),
        )
        oversized.data[KdfParametersStore.ITERATIONS_KEY] = "2000001"
        assertThrows(SecureStorageException::class.java) { KdfParametersStore(oversized).load() }
    }

    private class InMemoryValues(
        val data: MutableMap<String, String> = mutableMapOf(),
    ) : StringValueStore {
        override fun get(key: String): String? = data[key]

        override fun put(values: Map<String, String>): Boolean {
            data.putAll(values)
            return true
        }

        override fun remove(keys: Set<String>): Boolean {
            keys.forEach(data::remove)
            return true
        }
    }
}
