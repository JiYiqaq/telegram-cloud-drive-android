package com.teledrive.lite.settings

import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureConfigStoreTest {
    @Test
    fun connectionConfigRoundTripsWithoutPlaintextPreferences() {
        val values = InMemoryStringValueStore()
        val cipher = BindingCipher()
        val store = SecureConfigStore(values, cipher)
        val config = ValidatedConnectionConfig(dummyToken(), -1001234567890L)

        store.save(config)

        assertEquals(config, store.load())
        values.data.values.forEach { stored ->
            assertFalse(stored.contains(config.botToken))
            assertFalse(stored.contains(config.channelId.toString()))
        }
        assertFalse(store.load().toString().contains(config.botToken))
    }

    @Test
    fun missingConfigReturnsNullButPartialConfigIsCorrupted() {
        val values = InMemoryStringValueStore()
        val store = SecureConfigStore(values, BindingCipher())

        assertNull(store.load())
        values.data[SecureConfigStore.TOKEN_KEY] = "incomplete"

        val error = assertThrows(SecureStorageException::class.java) { store.load() }
        assertEquals(SecureStorageFailure.CORRUPTED, error.failure)
    }

    @Test
    fun associatedDataPreventsSwappingConfigCiphertexts() {
        val values = InMemoryStringValueStore()
        val store = SecureConfigStore(values, BindingCipher())
        store.save(ValidatedConnectionConfig(dummyToken(), -1001234567890L))
        val tokenBlob = values.data.getValue(SecureConfigStore.TOKEN_KEY)
        values.data[SecureConfigStore.TOKEN_KEY] =
            values.data.getValue(SecureConfigStore.CHANNEL_ID_KEY)
        values.data[SecureConfigStore.CHANNEL_ID_KEY] = tokenBlob

        val error = assertThrows(SecureStorageException::class.java) { store.load() }

        assertEquals(SecureStorageFailure.CORRUPTED, error.failure)
        assertFalse(error.message.orEmpty().contains(dummyToken()))
    }

    @Test
    fun clearingConfigRemovesValuesAndDeletesKeystoreKey() {
        val values = InMemoryStringValueStore()
        val cipher = BindingCipher()
        val store = SecureConfigStore(values, cipher)
        store.save(ValidatedConnectionConfig(dummyToken(), -1001234567890L))

        store.clear()

        assertTrue(values.data.isEmpty())
        assertTrue(cipher.deleted)
    }

    @Test
    fun sessionStoreRoundTripsOnlyAes256MasterKeyAndClearsIt() {
        val values = InMemoryStringValueStore()
        val cipher = BindingCipher()
        val store = SessionKeyStore(values, cipher)
        val masterKey = ByteArray(SessionKeyStore.MASTER_KEY_BYTES) { it.toByte() }

        store.save(masterKey)

        assertArrayEquals(masterKey, store.load())
        assertFalse(values.data.getValue(SessionKeyStore.SESSION_KEY).contains(masterKey.decodeToString()))
        store.clear()
        assertNull(store.load())
        assertTrue(cipher.deleted)
    }

    @Test
    fun sessionStoreRejectsWrongKeyLengthWithoutWriting() {
        val values = InMemoryStringValueStore()
        val store = SessionKeyStore(values, BindingCipher())

        assertThrows(IllegalArgumentException::class.java) {
            store.save(ByteArray(SessionKeyStore.MASTER_KEY_BYTES - 1))
        }

        assertTrue(values.data.isEmpty())
    }

    @Test
    fun configSaveAndClearAreSerializedAsOneTransaction() {
        val values = InMemoryStringValueStore()
        val cipher = BlockingBindingCipher()
        val store = SecureConfigStore(values, cipher)

        assertSaveAndClearAreSerialized(
            cipher = cipher,
            values = values,
            save = {
                store.save(ValidatedConnectionConfig(dummyToken(), -1001234567890L))
            },
            clear = store::clear,
        )
    }

    @Test
    fun sessionSaveAndClearAreSerializedAsOneTransaction() {
        val values = InMemoryStringValueStore()
        val cipher = BlockingBindingCipher()
        val store = SessionKeyStore(values, cipher)

        assertSaveAndClearAreSerialized(
            cipher = cipher,
            values = values,
            save = { store.save(ByteArray(SessionKeyStore.MASTER_KEY_BYTES)) },
            clear = store::clear,
        )
    }

    private fun assertSaveAndClearAreSerialized(
        cipher: BlockingBindingCipher,
        values: InMemoryStringValueStore,
        save: () -> Unit,
        clear: () -> Unit,
    ) {
        val saveFailure = AtomicReference<Throwable?>()
        val clearFailure = AtomicReference<Throwable?>()
        val saveThread = thread(start = true, name = "secure-save") {
            runCatching(save).exceptionOrNull()?.let(saveFailure::set)
        }
        assertTrue(cipher.encryptionStarted.await(2, TimeUnit.SECONDS))
        val clearThread = thread(start = true, name = "secure-clear") {
            runCatching(clear).exceptionOrNull()?.let(clearFailure::set)
        }

        try {
            waitUntilBlockedOrTerminated(clearThread)
            assertEquals(Thread.State.BLOCKED, clearThread.state)
        } finally {
            cipher.releaseEncryption.countDown()
            saveThread.join(2_000)
            clearThread.join(2_000)
        }

        assertFalse(saveThread.isAlive)
        assertFalse(clearThread.isAlive)
        assertNull(saveFailure.get())
        assertNull(clearFailure.get())
        assertTrue(values.data.isEmpty())
        assertTrue(cipher.deleted)
    }

    private fun waitUntilBlockedOrTerminated(target: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (target.isAlive && target.state != Thread.State.BLOCKED) {
            check(System.nanoTime() < deadline) { "clear thread did not reach a stable state" }
            Thread.yield()
        }
    }

    private class InMemoryStringValueStore : StringValueStore {
        val data = linkedMapOf<String, String>()

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

    private open class BindingCipher : SecretCipher {
        var deleted: Boolean = false
            private set

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val binding = MessageDigest.getInstance("SHA-256").digest(associatedData)
            return binding.copyOfRange(0, BINDING_BYTES) + plaintext.mapIndexed { index, byte ->
                (byte.toInt() xor binding[index % binding.size].toInt()).toByte()
            }.toByteArray()
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            val binding = MessageDigest.getInstance("SHA-256").digest(associatedData)
            check(ciphertext.copyOfRange(0, BINDING_BYTES).contentEquals(binding.copyOfRange(0, BINDING_BYTES)))
            return ciphertext.copyOfRange(BINDING_BYTES, ciphertext.size)
                .mapIndexed { index, byte ->
                    (byte.toInt() xor binding[index % binding.size].toInt()).toByte()
                }.toByteArray()
        }

        override fun deleteKey() {
            deleted = true
        }
    }

    private class BlockingBindingCipher : BindingCipher() {
        val encryptionStarted = CountDownLatch(1)
        val releaseEncryption = CountDownLatch(1)
        private val firstEncryption = AtomicBoolean(true)

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            if (firstEncryption.compareAndSet(true, false)) {
                encryptionStarted.countDown()
                check(releaseEncryption.await(2, TimeUnit.SECONDS)) {
                    "test did not release encryption"
                }
            }
            return super.encrypt(plaintext, associatedData)
        }
    }

    private fun dummyToken(): String =
        "123456789:" + "AA_TEST_ONLY_abcdefghijklmnopqrstuvwxyz"

    private companion object {
        const val BINDING_BYTES = 8
    }
}
