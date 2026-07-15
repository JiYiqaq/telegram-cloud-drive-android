package com.teledrive.lite.settings

import com.teledrive.lite.crypto.KeyDerivation
import com.teledrive.lite.crypto.KeyDerivationParameters
import com.teledrive.lite.util.SecureErase
import java.util.UUID

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
        try {
            encryptedToken = configCipher.encrypt(tokenBytes, SecureConfigStore.TOKEN_AAD)
            encryptedChannelId = configCipher.encrypt(channelIdBytes, SecureConfigStore.CHANNEL_ID_AAD)
            encryptedMasterKey = sessionCipher.encrypt(masterKey, SessionKeyStore.SESSION_KEY_AAD)
            val committed = values.put(
                mapOf(
                    SecureConfigStore.TOKEN_KEY to encryptedToken.toBase64(),
                    SecureConfigStore.CHANNEL_ID_KEY to encryptedChannelId.toBase64(),
                    SessionKeyStore.SESSION_KEY to encryptedMasterKey.toBase64(),
                    KdfParametersStore.ALGORITHM_KEY to parameters.algorithm,
                    KdfParametersStore.SALT_KEY to salt.toBase64(),
                    KdfParametersStore.ITERATIONS_KEY to parameters.iterations.toString(),
                    KdfParametersStore.KEY_LENGTH_KEY to parameters.keyLengthBytes.toString(),
                    GENERATION_KEY to UUID.randomUUID().toString(),
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
        }
    }

    @Synchronized
    fun isComplete(): Boolean = REQUIRED_KEYS.all { values.get(it) != null } &&
        values.get(FORMAT_KEY) == FORMAT_VERSION

    companion object {
        internal const val GENERATION_KEY = "setup_generation"
        internal const val FORMAT_KEY = "setup_state_format"
        private const val FORMAT_VERSION = "1"
        private val REQUIRED_KEYS = setOf(
            SecureConfigStore.TOKEN_KEY,
            SecureConfigStore.CHANNEL_ID_KEY,
            SessionKeyStore.SESSION_KEY,
            KdfParametersStore.ALGORITHM_KEY,
            KdfParametersStore.SALT_KEY,
            KdfParametersStore.ITERATIONS_KEY,
            KdfParametersStore.KEY_LENGTH_KEY,
            GENERATION_KEY,
            FORMAT_KEY,
        )
    }
}
