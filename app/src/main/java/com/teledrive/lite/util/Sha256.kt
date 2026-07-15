package com.teledrive.lite.util

import java.io.InputStream
import java.security.MessageDigest

object Sha256 {
    const val BUFFER_SIZE_BYTES: Int = 64 * 1024

    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance(ALGORITHM).digest(bytes).toHex()

    fun digest(input: InputStream): String {
        val messageDigest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        try {
            while (true) {
                val count = input.read(buffer)
                when {
                    count < 0 -> break
                    count > 0 -> messageDigest.update(buffer, 0, count)
                    else -> {
                        val nextByte = input.read()
                        if (nextByte < 0) break
                        messageDigest.update(nextByte.toByte())
                    }
                }
            }
        } finally {
            SecureErase.wipe(buffer)
        }
        return messageDigest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private const val ALGORITHM = "SHA-256"
}
