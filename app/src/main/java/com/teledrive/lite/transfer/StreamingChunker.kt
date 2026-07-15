package com.teledrive.lite.transfer

import com.teledrive.lite.crypto.CryptoEngine
import com.teledrive.lite.util.SecureErase
import java.io.InputStream

object StreamingChunker {
    const val MAX_ENCRYPTED_CHUNK_BYTES: Int = 20_000_000
    const val MAX_PLAINTEXT_CHUNK_SIZE_BYTES: Int =
        MAX_ENCRYPTED_CHUNK_BYTES - CryptoEngine.ENVELOPE_OVERHEAD_BYTES - 1
    const val DEFAULT_CHUNK_SIZE_BYTES: Int = 18 * 1024 * 1024

    fun forEachChunk(
        input: InputStream,
        chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES,
        consumer: (PlainChunk) -> Unit,
    ): Long {
        require(chunkSizeBytes in 1..MAX_PLAINTEXT_CHUNK_SIZE_BYTES) {
            "Chunk size exceeds the Telegram download boundary"
        }

        val buffer = ByteArray(chunkSizeBytes)
        var index = 0
        var totalBytes = 0L
        var emittedAnyChunk = false

        try {
            while (true) {
                val bytesRead = fillChunk(input, buffer)
                if (bytesRead == 0) {
                    if (!emittedAnyChunk) consumer(PlainChunk(index = 0, bytes = byteArrayOf()))
                    return totalBytes
                }

                val chunkBytes = buffer.copyOf(bytesRead)
                try {
                    consumer(PlainChunk(index = index++, bytes = chunkBytes))
                } finally {
                    SecureErase.wipe(chunkBytes)
                }
                emittedAnyChunk = true
                totalBytes += bytesRead
                if (bytesRead < chunkSizeBytes) return totalBytes
            }
        } finally {
            SecureErase.wipe(buffer)
        }
    }

    private fun fillChunk(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            when {
                count < 0 -> return offset
                count > 0 -> offset += count
                else -> {
                    val nextByte = input.read()
                    if (nextByte < 0) return offset
                    buffer[offset++] = nextByte.toByte()
                }
            }
        }
        return offset
    }
}
