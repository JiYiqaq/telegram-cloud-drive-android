package com.teledrive.lite.settings

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretKeyProvider {
    fun getOrCreate(): SecretKey

    fun delete()
}

class AesGcmSecretCipher(
    private val keyProvider: SecretKeyProvider,
    private val secureRandom: SecureRandom = SecureRandom(),
) : SecretCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreate(), secureRandom)
        }
        val nonce = checkNotNull(cipher.iv).also {
            check(it.size == NONCE_BYTES) { "AES-GCM provider returned an invalid nonce" }
        }
        val header = createHeader()
        cipher.apply {
            updateAAD(header)
            updateAAD(associatedData)
        }
        return header + nonce + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        require(ciphertext.size >= HEADER_BYTES + NONCE_BYTES + TAG_BYTES) {
            "Encrypted envelope is truncated"
        }
        val header = ciphertext.copyOfRange(0, HEADER_BYTES)
        require(header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Encrypted envelope magic is invalid"
        }
        require(header[VERSION_OFFSET] == FORMAT_VERSION) {
            "Encrypted envelope version is unsupported"
        }
        require(header[NONCE_LENGTH_OFFSET].toInt() == NONCE_BYTES) {
            "Encrypted envelope nonce length is invalid"
        }
        val nonce = ciphertext.copyOfRange(HEADER_BYTES, HEADER_BYTES + NONCE_BYTES)
        val payload = ciphertext.copyOfRange(HEADER_BYTES + NONCE_BYTES, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                keyProvider.getOrCreate(),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            updateAAD(header)
            updateAAD(associatedData)
        }
        return cipher.doFinal(payload)
    }

    override fun deleteKey() {
        keyProvider.delete()
    }

    private fun createHeader(): ByteArray =
        MAGIC + byteArrayOf(FORMAT_VERSION, NONCE_BYTES.toByte())

    companion object {
        internal const val VERSION_OFFSET: Int = 4
        private const val NONCE_LENGTH_OFFSET: Int = 5
        private const val HEADER_BYTES: Int = 6
        private const val NONCE_BYTES: Int = 12
        private const val TAG_BYTES: Int = 16
        private const val TAG_BITS: Int = TAG_BYTES * 8
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'D'.code.toByte(), 'K'.code.toByte(), 'C'.code.toByte())
    }
}
