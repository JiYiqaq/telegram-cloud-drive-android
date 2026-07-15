package com.teledrive.lite.settings

class SessionKeyStore(
    private val values: StringValueStore,
    private val cipher: SecretCipher,
) {
    @Synchronized
    fun save(masterKey: ByteArray) {
        require(masterKey.size == MASTER_KEY_BYTES)
        var encrypted: ByteArray? = null
        try {
            encrypted = cipher.encrypt(masterKey, SESSION_KEY_AAD)
            if (!values.put(mapOf(SESSION_KEY to encrypted.toBase64()))) {
                throw SecureStorageException(SecureStorageFailure.WRITE_FAILED)
            }
        } catch (error: SecureStorageException) {
            throw error
        } catch (_: Exception) {
            throw SecureStorageException(SecureStorageFailure.CRYPTO_FAILURE)
        } finally {
            encrypted?.fill(0)
        }
    }

    @Synchronized
    fun load(): ByteArray? {
        val encoded = values.get(SESSION_KEY) ?: return null
        var encrypted: ByteArray? = null
        return try {
            encrypted = encoded.fromBase64()
            cipher.decrypt(encrypted, SESSION_KEY_AAD).also { masterKey ->
                if (masterKey.size != MASTER_KEY_BYTES) {
                    masterKey.fill(0)
                    throw SecureStorageException(SecureStorageFailure.CORRUPTED)
                }
            }
        } catch (error: SecureStorageException) {
            throw error
        } catch (_: Exception) {
            throw SecureStorageException(SecureStorageFailure.CORRUPTED)
        } finally {
            encrypted?.fill(0)
        }
    }

    @Synchronized
    fun clear() {
        var failed = false
        try {
            failed = !values.remove(setOf(SESSION_KEY))
        } catch (_: Exception) {
            failed = true
        }
        try {
            cipher.deleteKey()
        } catch (_: Exception) {
            failed = true
        }
        if (failed) throw SecureStorageException(SecureStorageFailure.WRITE_FAILED)
    }

    companion object {
        const val MASTER_KEY_BYTES: Int = 32
        internal const val SESSION_KEY = "encrypted_session_master_key"
        private val SESSION_KEY_AAD = "teledrive.session.master-key.v1".encodeToByteArray()
    }
}
