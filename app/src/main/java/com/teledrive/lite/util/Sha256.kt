package com.teledrive.lite.util

import java.io.InputStream
import java.security.MessageDigest

data class Sha256Digest(
    val hex: String,
    val byteCount: Long,
)

object Sha256 {
    const val BUFFER_SIZE_BYTES: Int = 64 * 1024

    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance(ALGORITHM).digest(bytes).toHex()

    fun digest(input: InputStream): String = digestAndCount(input).hex

    fun digestAndCount(input: InputStream): Sha256Digest {
        val messageDigest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        var totalBytes = 0L
        try {
            while (true) {
                val count = input.read(buffer)
                when {
                    count < 0 -> break
                    count > 0 -> {
                        messageDigest.update(buffer, 0, count)
                        totalBytes += count
                    }
                    else -> {
                        val nextByte = input.read()
                        if (nextByte < 0) break
                        messageDigest.update(nextByte.toByte())
                        totalBytes += 1
                    }
                }
            }
        } finally {
            SecureErase.wipe(buffer)
        }
        return Sha256Digest(messageDigest.digest().toHex(), totalBytes)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private const val ALGORITHM = "SHA-256"
}
