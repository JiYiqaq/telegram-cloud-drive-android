package com.teledrive.lite.settings

import java.util.Base64

enum class SecureStorageFailure(val safeMessage: String) {
    CORRUPTED("Encrypted local configuration is corrupted"),
    WRITE_FAILED("Encrypted local configuration could not be saved"),
    CRYPTO_FAILURE("Encrypted local configuration is unavailable"),
}

class SecureStorageException(
    val failure: SecureStorageFailure,
) : IllegalStateException(failure.safeMessage)

class SecureConfigStore(
    private val values: StringValueStore,
    private val cipher: SecretCipher,
) {
    @Synchronized
    fun save(config: ValidatedConnectionConfig) {
        val tokenBytes = config.botToken.encodeToByteArray()
        val channelIdBytes = config.channelId.toString().encodeToByteArray()
        var encryptedToken: ByteArray? = null
        var encryptedChannelId: ByteArray? = null
        try {
            encryptedToken = cipher.encrypt(tokenBytes, TOKEN_AAD)
            encryptedChannelId = cipher.encrypt(channelIdBytes, CHANNEL_ID_AAD)
            val saved = values.put(
                mapOf(
                    TOKEN_KEY to encryptedToken.toBase64(),
                    CHANNEL_ID_KEY to encryptedChannelId.toBase64(),
                ),
            )
            if (!saved) throw SecureStorageException(SecureStorageFailure.WRITE_FAILED)
        } catch (error: SecureStorageException) {
            throw error
        } catch (_: Exception) {
            throw SecureStorageException(SecureStorageFailure.CRYPTO_FAILURE)
        } finally {
            tokenBytes.fill(0)
            channelIdBytes.fill(0)
            encryptedToken?.fill(0)
            encryptedChannelId?.fill(0)
        }
    }

    @Synchronized
    fun load(): ValidatedConnectionConfig? {
        val encodedToken = values.get(TOKEN_KEY)
        val encodedChannelId = values.get(CHANNEL_ID_KEY)
        if (encodedToken == null && encodedChannelId == null) return null
        if (encodedToken == null || encodedChannelId == null) {
            throw SecureStorageException(SecureStorageFailure.CORRUPTED)
        }

        var encryptedToken: ByteArray? = null
        var encryptedChannelId: ByteArray? = null
        var tokenBytes: ByteArray? = null
        var channelIdBytes: ByteArray? = null
        return try {
            encryptedToken = encodedToken.fromBase64()
            encryptedChannelId = encodedChannelId.fromBase64()
            tokenBytes = cipher.decrypt(encryptedToken, TOKEN_AAD)
            channelIdBytes = cipher.decrypt(encryptedChannelId, CHANNEL_ID_AAD)
            val token = tokenBytes.decodeToString()
            val channelId = channelIdBytes.decodeToString().toLongOrNull()
                ?: throw SecureStorageException(SecureStorageFailure.CORRUPTED)
            if (token.isBlank()) throw SecureStorageException(SecureStorageFailure.CORRUPTED)
            ValidatedConnectionConfig(token, channelId)
        } catch (error: SecureStorageException) {
            throw error
        } catch (_: Exception) {
            throw SecureStorageException(SecureStorageFailure.CORRUPTED)
        } finally {
            encryptedToken?.fill(0)
            encryptedChannelId?.fill(0)
            tokenBytes?.fill(0)
            channelIdBytes?.fill(0)
        }
    }

    @Synchronized
    fun clear() {
        var failed = false
        try {
            failed = !values.remove(setOf(TOKEN_KEY, CHANNEL_ID_KEY))
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
        internal const val TOKEN_KEY = "encrypted_bot_token"
        internal const val CHANNEL_ID_KEY = "encrypted_channel_id"
        private val TOKEN_AAD = "teledrive.config.bot-token.v1".encodeToByteArray()
        private val CHANNEL_ID_AAD = "teledrive.config.channel-id.v1".encodeToByteArray()
    }
}

internal fun ByteArray.toBase64(): String =
    Base64.getEncoder().withoutPadding().encodeToString(this)

internal fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)
