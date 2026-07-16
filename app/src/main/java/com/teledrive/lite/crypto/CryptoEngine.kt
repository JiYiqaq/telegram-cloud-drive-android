/*
 * Initial implementation created with OpenAI Codex
 * based on requirements provided by the project maintainer.
 */

package com.teledrive.lite.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoEngine(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun generateDataKey(): ByteArray = ByteArray(DATA_KEY_BYTES).also(secureRandom::nextBytes)

    fun encryptChunk(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        requireAes256Key(key)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM), secureRandom)
        }
        val nonce = cipher.iv ?: throw CryptoOperationException()
        if (nonce.size != NONCE_BYTES) throw CryptoOperationException()
        val header = createHeader()
        cipher.updateAAD(header)
        cipher.updateAAD(associatedData)
        return header + nonce + cipher.doFinal(plaintext)
    }

    fun decryptChunk(
        key: ByteArray,
        envelope: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        requireAes256Key(key)
        val header = parseHeader(envelope)
        val nonce = envelope.copyOfRange(HEADER_BYTES, HEADER_BYTES + NONCE_BYTES)
        val ciphertext = envelope.copyOfRange(HEADER_BYTES + NONCE_BYTES, envelope.size)
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, KEY_ALGORITHM),
                    GCMParameterSpec(TAG_BITS, nonce),
                )
                updateAAD(header)
                updateAAD(associatedData)
                doFinal(ciphertext)
            }
        } catch (_: AEADBadTagException) {
            throw CryptoAuthenticationException()
        } catch (_: BadPaddingException) {
            throw CryptoAuthenticationException()
        }
    }

    fun extractNonce(envelope: ByteArray): ByteArray {
        parseHeader(envelope)
        return envelope.copyOfRange(HEADER_BYTES, HEADER_BYTES + NONCE_BYTES)
    }

    private fun parseHeader(envelope: ByteArray): ByteArray {
        if (envelope.size < ENVELOPE_OVERHEAD_BYTES) throw CryptoFormatException()
        val header = envelope.copyOfRange(0, HEADER_BYTES)
        if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw CryptoFormatException()
        }
        if (header[VERSION_OFFSET] != FORMAT_VERSION) throw CryptoFormatException()
        if (header[NONCE_LENGTH_OFFSET].toInt() != NONCE_BYTES) {
            throw CryptoFormatException()
        }
        return header
    }

    private fun createHeader(): ByteArray =
        MAGIC + byteArrayOf(FORMAT_VERSION, NONCE_BYTES.toByte())

    private fun requireAes256Key(key: ByteArray) {
        require(key.size == DATA_KEY_BYTES) { "AES-256 key must contain 32 bytes" }
    }

    companion object {
        const val DATA_KEY_BYTES: Int = 32
        const val ENVELOPE_OVERHEAD_BYTES: Int = 34
        internal const val VERSION_OFFSET: Int = 4
        private const val NONCE_LENGTH_OFFSET: Int = 5
        private const val HEADER_BYTES: Int = 6
        private const val NONCE_BYTES: Int = 12
        private const val TAG_BYTES: Int = 16
        private const val TAG_BITS: Int = TAG_BYTES * 8
        private const val FORMAT_VERSION: Byte = 1
        private const val KEY_ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val MAGIC = byteArrayOf(
            'T'.code.toByte(),
            'D'.code.toByte(),
            'F'.code.toByte(),
            'C'.code.toByte(),
        )
    }
}
