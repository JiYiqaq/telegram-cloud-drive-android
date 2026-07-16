package com.teledrive.lite.util

import java.io.ByteArrayOutputStream
import java.io.OutputStream

class BoundedOutputExceededException : IllegalStateException(
    "Output exceeded the configured byte limit",
)

class BoundedByteArrayOutputStream(
    private val maximumBytes: Int,
) : OutputStream() {
    private val delegate: ByteArrayOutputStream

    init {
        require(maximumBytes >= 0)
        delegate = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_INITIAL_CAPACITY))
    }

    override fun write(value: Int) {
        requireCapacity(1)
        delegate.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw IndexOutOfBoundsException()
        }
        requireCapacity(length)
        delegate.write(bytes, offset, length)
    }

    fun size(): Int = delegate.size()

    fun toByteArray(): ByteArray = delegate.toByteArray()

    private fun requireCapacity(additionalBytes: Int) {
        if (additionalBytes > maximumBytes - delegate.size()) {
            throw BoundedOutputExceededException()
        }
    }

    private companion object {
        const val DEFAULT_INITIAL_CAPACITY = 8 * 1024
    }
}
