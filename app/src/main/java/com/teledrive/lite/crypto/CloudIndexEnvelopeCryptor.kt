package com.teledrive.lite.crypto

import com.teledrive.lite.util.SecureErase
import java.nio.ByteBuffer

data class CloudIndexEnvelopeMetadata(
    val envelopeFormatVersion: Int,
    val keyDerivation: KeyDerivationParameters,
)

sealed class CloudIndexEnvelopeException(message: String) : IllegalArgumentException(message)

class InvalidCloudIndexEnvelopeException : CloudIndexEnvelopeException(
    "Encrypted cloud index envelope is invalid",
)

class UnsupportedCloudIndexEnvelopeVersionException(
    val version: Int,
) : CloudIndexEnvelopeException("Encrypted cloud index envelope version is unsupported")

class UnsupportedCloudIndexKdfException(
    val kdfId: Int,
) : CloudIndexEnvelopeException("Encrypted cloud index key derivation algorithm is unsupported")

class CloudIndexEnvelopeCryptor(
    private val cryptoEngine: CryptoEngine,
) {
    fun encrypt(
        plaintext: ByteArray,
        masterKey: ByteArray,
        keyDerivation: KeyDerivationParameters,
    ): ByteArray {
        if (keyDerivation.algorithm != KeyDerivation.ALGORITHM) {
            throw UnsupportedCloudIndexKdfException(UNKNOWN_KDF_ID)
        }
        val header = createHeader(keyDerivation)
        val maximumPlaintextBytes = MAX_ENVELOPE_BYTES - header.size -
            CryptoEngine.ENVELOPE_OVERHEAD_BYTES
        if (plaintext.size > maximumPlaintextBytes) {
            throw InvalidCloudIndexEnvelopeException()
        }
        val ciphertext = cryptoEngine.encryptChunk(
            key = masterKey,
            plaintext = plaintext,
            associatedData = associatedData(header),
        )
        return header + ciphertext
    }

    fun inspect(envelope: ByteArray): CloudIndexEnvelopeMetadata = parse(envelope).metadata

    fun decrypt(envelope: ByteArray, masterKey: ByteArray): ByteArray {
        val parsed = parse(envelope)
        return cryptoEngine.decryptChunk(
            key = masterKey,
            envelope = parsed.ciphertext,
            associatedData = associatedData(parsed.header),
        )
    }

    fun decryptWithPassword(envelope: ByteArray, password: CharArray): ByteArray {
        val metadata = inspect(envelope)
        val masterKey = KeyDerivation.derive(password, metadata.keyDerivation)
        return try {
            decrypt(envelope, masterKey)
        } finally {
            SecureErase.wipe(masterKey)
        }
    }

    private fun createHeader(parameters: KeyDerivationParameters): ByteArray {
        val salt = parameters.salt
        return try {
            if (salt.isEmpty() || salt.size > KeyDerivation.MAX_SALT_BYTES) {
                throw InvalidCloudIndexEnvelopeException()
            }
            ByteBuffer.allocate(FIXED_HEADER_BYTES + salt.size)
                .put(MAGIC)
                .put(FORMAT_VERSION.toByte())
                .put(PBKDF2_SHA256_KDF_ID.toByte())
                .put(salt.size.toByte())
                .put(salt)
                .putInt(parameters.iterations)
                .put(parameters.keyLengthBytes.toByte())
                .array()
        } finally {
            SecureErase.wipe(salt)
        }
    }

    private fun parse(envelope: ByteArray): ParsedEnvelope {
        if (envelope.size !in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) {
            throw InvalidCloudIndexEnvelopeException()
        }
        val buffer = ByteBuffer.wrap(envelope)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) throw InvalidCloudIndexEnvelopeException()

        val version = buffer.get().toUnsignedInt()
        if (version != FORMAT_VERSION) {
            throw UnsupportedCloudIndexEnvelopeVersionException(version)
        }
        val kdfId = buffer.get().toUnsignedInt()
        if (kdfId != PBKDF2_SHA256_KDF_ID) throw UnsupportedCloudIndexKdfException(kdfId)

        val saltLength = buffer.get().toUnsignedInt()
        if (saltLength !in 1..KeyDerivation.MAX_SALT_BYTES) {
            throw InvalidCloudIndexEnvelopeException()
        }
        val headerLength = FIXED_HEADER_BYTES + saltLength
        if (envelope.size < headerLength + CryptoEngine.ENVELOPE_OVERHEAD_BYTES) {
            throw InvalidCloudIndexEnvelopeException()
        }
        val salt = ByteArray(saltLength).also(buffer::get)
        val iterations = buffer.int
        val keyLengthBytes = buffer.get().toUnsignedInt()
        val parameters = try {
            KeyDerivationParameters.pbkdf2(
                salt = salt,
                iterations = iterations,
                keyLengthBytes = keyLengthBytes,
            )
        } catch (_: IllegalArgumentException) {
            throw InvalidCloudIndexEnvelopeException()
        } finally {
            SecureErase.wipe(salt)
        }
        return ParsedEnvelope(
            metadata = CloudIndexEnvelopeMetadata(version, parameters),
            header = envelope.copyOfRange(0, headerLength),
            ciphertext = envelope.copyOfRange(headerLength, envelope.size),
        )
    }

    private fun associatedData(header: ByteArray): ByteArray = INDEX_CONTEXT + header

    private fun Byte.toUnsignedInt(): Int = toInt() and 0xff

    private data class ParsedEnvelope(
        val metadata: CloudIndexEnvelopeMetadata,
        val header: ByteArray,
        val ciphertext: ByteArray,
    )

    companion object {
        const val MAX_ENVELOPE_BYTES: Int = 19_999_999
        internal const val VERSION_OFFSET: Int = 4
        internal const val KDF_OFFSET: Int = 5
        internal const val SALT_OFFSET: Int = 7

        private const val FORMAT_VERSION = 1
        private const val PBKDF2_SHA256_KDF_ID = 1
        private const val UNKNOWN_KDF_ID = -1
        private const val FIXED_HEADER_BYTES = 12
        private const val MIN_ENVELOPE_BYTES = FIXED_HEADER_BYTES + 1 +
            CryptoEngine.ENVELOPE_OVERHEAD_BYTES
        private val MAGIC = "TDIX".encodeToByteArray()
        private val INDEX_CONTEXT = "teledrive.cloud-index-envelope.v1\u0000".encodeToByteArray()
    }
}
