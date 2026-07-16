package com.teledrive.lite.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedByteArrayOutputStreamTest {
    @Test
    fun acceptsExactlyTheConfiguredLimit() {
        val output = BoundedByteArrayOutputStream(4)

        output.write(byteArrayOf(1, 2))
        output.write(byteArrayOf(3, 4), 0, 2)

        assertEquals(4, output.size())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
    }

    @Test
    fun rejectsOverflowWithoutWritingPartialInput() {
        val output = BoundedByteArrayOutputStream(3)
        output.write(byteArrayOf(1, 2))

        assertThrows(BoundedOutputExceededException::class.java) {
            output.write(byteArrayOf(3, 4))
        }
        assertArrayEquals(byteArrayOf(1, 2), output.toByteArray())
    }

    @Test
    fun validatesLimitsAndWriteRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedByteArrayOutputStream(-1)
        }
        val output = BoundedByteArrayOutputStream(4)
        assertThrows(IndexOutOfBoundsException::class.java) {
            output.write(byteArrayOf(1), 1, 1)
        }
    }
}
