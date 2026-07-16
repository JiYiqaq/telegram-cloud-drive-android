package com.teledrive.lite.settings

import com.teledrive.lite.crypto.KeyDerivation
import com.teledrive.lite.crypto.KeyDerivationParameters
import com.teledrive.lite.util.SecureErase
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface SetupStatePersistence {
    fun commit(
        config: ValidatedConnectionConfig,
        masterKey: ByteArray,
        parameters: KeyDerivationParameters,
    )
}

/**
 * Encrypts every sensitive setup field first, then commits the complete setup
 * generation in one SharedPreferences editor transaction.
 */
class AtomicSetupStateStore(
    private val values: StringValueStore,
    private val configCipher: SecretCipher,
    private val sessionCipher: SecretCipher,
) : SetupStatePersistence {
    @Synchronized
    override fun commit(
        config: ValidatedConnectionConfig,
        masterKey: ByteArray,
        parameters: KeyDerivationParameters,
    ) {
        require(masterKey.size == KeyDerivation.MASTER_KEY_BYTES)
        require(parameters.algorithm == KeyDerivation.ALGORITHM)

        val tokenBytes = config.botToken.encodeToByteArray()
        val channelIdBytes = config.channelId.toString().encodeToByteArray()
        val salt = parameters.salt
        var encryptedToken: ByteArray? = null
        var encryptedChannelId: ByteArray? = null
        var encryptedMasterKey: ByteArray? = null
        var binding: ByteArray? = null
        try {
            val generation = UUID.randomUUID().toString()
            encryptedToken = configCipher.encrypt(tokenBytes, SecureConfigStore.TOKEN_AAD)
            encryptedChannelId = configCipher.encrypt(channelIdBytes, SecureConfigStore.CHANNEL_ID_AAD)
            encryptedMasterKey = sessionCipher.encrypt(masterKey, SessionKeyStore.SESSION_KEY_AAD)
            binding = createBinding(masterKey, parameters, generation)
            val committed = values.put(
                mapOf(
                    SecureConfigStore.TOKEN_KEY to encryptedToken.toBase64(),
                    SecureConfigStore.CHANNEL_ID_KEY to encryptedChannelId.toBase64(),
                    SessionKeyStore.SESSION_KEY to encryptedMasterKey.toBase64(),
                    KdfParametersStore.ALGORITHM_KEY to parameters.algorithm,
                    KdfParametersStore.SALT_KEY to salt.toBase64(),
                    KdfParametersStore.ITERATIONS_KEY to parameters.iterations.toString(),
                    KdfParametersStore.KEY_LENGTH_KEY to parameters.keyLengthBytes.toString(),
                    GENERATION_KEY to generation,
                    CRYPTO_BINDING_KEY to binding.toBase64(),
                    FORMAT_KEY to FORMAT_VERSION,
                ),
            )
            if (!committed) throw SecureStorageException(SecureStorageFailure.WRITE_FAILED)
        } finally {
            SecureErase.wipe(tokenBytes)
            SecureErase.wipe(channelIdBytes)
            SecureErase.wipe(salt)
            encryptedToken?.let(SecureErase::wipe)
            encryptedChannelId?.let(SecureErase::wipe)
            encryptedMasterKey?.let(SecureErase::wipe)
            binding?.let(SecureErase::wipe)
        }
    }

    @Synchronized
    fun isComplete(): Boolean = REQUIRED_KEYS.all { values.get(it) != null } &&
        values.get(FORMAT_KEY) == FORMAT_VERSION

    /** Loads the master key and KDF metadata only after their atomic-generation HMAC matches. */
    @Synchronized
    fun loadCryptoContext(): SetupCryptoContext? {
        val present = REQUIRED_KEYS.associateWith(values::get)
        if (present.values.all { it == null }) return null
        if (present.values.any { it == null } || present[FORMAT_KEY] != FORMAT_VERSION) corrupted()

        var encryptedMasterKey: ByteArray? = null
        var masterKey: ByteArray? = null
        var expectedBinding: ByteArray? = null
        var actualBinding: ByteArray? = null
        return try {
            val generation = checkNotNull(present[GENERATION_KEY])
            val parameters = KdfParametersStore(values).load() ?: corrupted()
            encryptedMasterKey = checkNotNull(present[SessionKeyStore.SESSION_KEY]).fromBase64()
            masterKey = sessionCipher.decrypt(encryptedMasterKey, SessionKeyStore.SESSION_KEY_AAD)
            if (masterKey.size != KeyDerivation.MASTER_KEY_BYTES) corrupted()
            expectedBinding = checkNotNull(present[CRYPTO_BINDING_KEY]).fromBase64()
            actualBinding = createBinding(masterKey, parameters, generation)
            if (!MessageDigest.isEqual(expectedBinding, actualBinding)) corrupted()
            SetupCryptoContext(masterKey, parameters)
        } catch (error: SecureStorageException) {
            throw error
        } catch (_: Exception) {
            corrupted()
        } finally {
            encryptedMasterKey?.let(SecureErase::wipe)
            masterKey?.let(SecureErase::wipe)
            expectedBinding?.let(SecureErase::wipe)
            actualBinding?.let(SecureErase::wipe)
        }
    }

    @Synchronized
    fun clear() {
        var failed = !values.remove(REQUIRED_KEYS)
        runCatching { configCipher.deleteKey() }.onFailure { failed = true }
        runCatching { sessionCipher.deleteKey() }.onFailure { failed = true }
        if (failed) throw SecureStorageException(SecureStorageFailure.WRITE_FAILED)
    }

    private fun createBinding(
        masterKey: ByteArray,
        parameters: KeyDerivationParameters,
        generation: String,
    ): ByteArray {
        val salt = parameters.salt
        val encoded = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF(BINDING_CONTEXT)
                output.writeUTF(generation)
                output.writeUTF(parameters.algorithm)
                output.writeInt(salt.size)
                output.write(salt)
                output.writeInt(parameters.iterations)
                output.writeInt(parameters.keyLengthBytes)
            }
            bytes.toByteArray()
        }
        return try {
            Mac.getInstance(HMAC_ALGORITHM).run {
                init(SecretKeySpec(masterKey, HMAC_ALGORITHM))
                doFinal(encoded)
            }
        } finally {
            SecureErase.wipe(salt)
            SecureErase.wipe(encoded)
        }
    }

    private fun corrupted(): Nothing =
        throw SecureStorageException(SecureStorageFailure.CORRUPTED)

    companion object {
        internal const val GENERATION_KEY = "setup_generation"
        internal const val CRYPTO_BINDING_KEY = "setup_crypto_binding"
        internal const val FORMAT_KEY = "setup_state_format"
        private const val FORMAT_VERSION = "2"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val BINDING_CONTEXT = "teledrive.setup-crypto-binding.v1"
        private val REQUIRED_KEYS = setOf(
            SecureConfigStore.TOKEN_KEY,
            SecureConfigStore.CHANNEL_ID_KEY,
            SessionKeyStore.SESSION_KEY,
            KdfParametersStore.ALGORITHM_KEY,
            KdfParametersStore.SALT_KEY,
            KdfParametersStore.ITERATIONS_KEY,
            KdfParametersStore.KEY_LENGTH_KEY,
            GENERATION_KEY,
            CRYPTO_BINDING_KEY,
            FORMAT_KEY,
        )
    }
}

class SetupCryptoContext internal constructor(
    masterKey: ByteArray,
    val keyDerivation: KeyDerivationParameters,
) : AutoCloseable {
    private val key = masterKey.copyOf()

    @Synchronized
    fun <T> withMasterKey(block: (ByteArray) -> T): T {
        val copy = key.copyOf()
        return try {
            block(copy)
        } finally {
            SecureErase.wipe(copy)
        }
    }

    @Synchronized
    override fun close() {
        SecureErase.wipe(key)
    }
}
