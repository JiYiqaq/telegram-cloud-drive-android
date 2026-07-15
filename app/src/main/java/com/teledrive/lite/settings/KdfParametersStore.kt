package com.teledrive.lite.settings

import com.teledrive.lite.crypto.KeyDerivation
import com.teledrive.lite.crypto.KeyDerivationParameters

interface KdfParametersPersistence {
    fun save(parameters: KeyDerivationParameters)

    fun load(): KeyDerivationParameters?

    fun clear()
}

class KdfParametersStore(
    private val values: StringValueStore,
) : KdfParametersPersistence {
    @Synchronized
    override fun save(parameters: KeyDerivationParameters) {
        require(parameters.algorithm == KeyDerivation.ALGORITHM)
        val salt = parameters.salt
        try {
            val saved = values.put(
                mapOf(
                    ALGORITHM_KEY to parameters.algorithm,
                    SALT_KEY to salt.toBase64(),
                    ITERATIONS_KEY to parameters.iterations.toString(),
                    KEY_LENGTH_KEY to parameters.keyLengthBytes.toString(),
                ),
            )
            if (!saved) throw SecureStorageException(SecureStorageFailure.WRITE_FAILED)
        } finally {
            salt.fill(0)
        }
    }

    @Synchronized
    override fun load(): KeyDerivationParameters? {
        val fields = listOf(ALGORITHM_KEY, SALT_KEY, ITERATIONS_KEY, KEY_LENGTH_KEY)
            .associateWith(values::get)
        if (fields.values.all { it == null }) return null
        if (fields.values.any { it == null }) corrupted()

        var salt: ByteArray? = null
        return try {
            val algorithm = checkNotNull(fields[ALGORITHM_KEY])
            if (algorithm != KeyDerivation.ALGORITHM) corrupted()
            salt = checkNotNull(fields[SALT_KEY]).fromBase64()
            KeyDerivationParameters.pbkdf2(
                salt = salt,
                iterations = checkNotNull(fields[ITERATIONS_KEY]).toInt(),
                keyLengthBytes = checkNotNull(fields[KEY_LENGTH_KEY]).toInt(),
            )
        } catch (error: SecureStorageException) {
            throw error
        } catch (_: Exception) {
            corrupted()
        } finally {
            salt?.fill(0)
        }
    }

    @Synchronized
    override fun clear() {
        if (!values.remove(setOf(ALGORITHM_KEY, SALT_KEY, ITERATIONS_KEY, KEY_LENGTH_KEY))) {
            throw SecureStorageException(SecureStorageFailure.WRITE_FAILED)
        }
    }

    private fun corrupted(): Nothing =
        throw SecureStorageException(SecureStorageFailure.CORRUPTED)

    companion object {
        internal const val ALGORITHM_KEY = "kdf_algorithm"
        internal const val SALT_KEY = "kdf_salt"
        internal const val ITERATIONS_KEY = "kdf_iterations"
        internal const val KEY_LENGTH_KEY = "kdf_key_length"
    }
}
