package com.teledrive.lite.transfer

import com.teledrive.lite.crypto.CryptoEngine
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingChunkerTest {
    @Test
    fun emptyInputProducesOneEmptyChunk() {
        val chunks = mutableListOf<PlainChunk>()

        val total = StreamingChunker.forEachChunk(
            input = ByteArrayInputStream(byteArrayOf()),
            chunkSizeBytes = 4,
            consumer = chunks::add,
        )

        assertEquals(0L, total)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks.single().index)
        assertTrue(chunks.single().bytes.isEmpty())
    }

    @Test
    fun exactBoundaryDoesNotProduceExtraEmptyChunk() {
        val data = byteArrayOf(1, 2, 3, 4)
        val chunks = mutableListOf<PlainChunk>()

        val total = StreamingChunker.forEachChunk(
            input = ByteArrayInputStream(data),
            chunkSizeBytes = 4,
            consumer = { chunks += it.retainedCopy() },
        )

        assertEquals(4L, total)
        assertEquals(1, chunks.size)
        assertArrayEquals(data, chunks.single().bytes)
    }

    @Test
    fun chunksStayOrderedAndRecombineToOriginal() {
        val data = ByteArray(10) { it.toByte() }
        val input = TrackingInputStream(data)
        val chunks = mutableListOf<PlainChunk>()

        val total = StreamingChunker.forEachChunk(input, 4) { chunks += it.retainedCopy() }

        assertEquals(10L, total)
        assertEquals(listOf(0, 1, 2), chunks.map(PlainChunk::index))
        assertEquals(listOf(4, 4, 2), chunks.map { it.bytes.size })
        assertArrayEquals(data, chunks.flatMap { it.bytes.asIterable() }.toByteArray())
        assertTrue(input.maxRequestedBytes <= 4)
        assertFalse(input.closed)
    }

    @Test
    fun chunkSizeIsLimitedToEncryptedTelegramDownloadBoundary() {
        assertEquals(18 * 1024 * 1024, StreamingChunker.DEFAULT_CHUNK_SIZE_BYTES)
        assertTrue(
            StreamingChunker.DEFAULT_CHUNK_SIZE_BYTES + CryptoEngine.ENVELOPE_OVERHEAD_BYTES <
                StreamingChunker.MAX_ENCRYPTED_CHUNK_BYTES,
        )
        assertThrows(IllegalArgumentException::class.java) {
            StreamingChunker.forEachChunk(
                ByteArrayInputStream(byteArrayOf()),
                StreamingChunker.MAX_PLAINTEXT_CHUNK_SIZE_BYTES + 1,
            ) {}
        }
    }

    @Test
    fun plaintextChunkCopiesAreWipedAfterConsumerReturnsOrThrows() {
        lateinit var returnedBytes: ByteArray
        StreamingChunker.forEachChunk(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 4) { chunk ->
            returnedBytes = chunk.bytes
            assertArrayEquals(byteArrayOf(1, 2, 3), chunk.bytes)
        }
        assertTrue(returnedBytes.all { it == 0.toByte() })

        lateinit var failedBytes: ByteArray
        assertThrows(IllegalStateException::class.java) {
            StreamingChunker.forEachChunk(ByteArrayInputStream(byteArrayOf(4, 5, 6)), 4) { chunk ->
                failedBytes = chunk.bytes
                throw IllegalStateException("consumer failed")
            }
        }
        assertTrue(failedBytes.all { it == 0.toByte() })
    }

    private class TrackingInputStream(data: ByteArray) : ByteArrayInputStream(data) {
        var maxRequestedBytes: Int = 0
            private set
        var closed: Boolean = false
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            maxRequestedBytes = maxOf(maxRequestedBytes, length)
            return super.read(buffer, offset, length)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun PlainChunk.retainedCopy(): PlainChunk = copy(bytes = bytes.copyOf())
}
