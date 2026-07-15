package com.teledrive.lite.util

object SecureErase {
    fun wipe(bytes: ByteArray) {
        bytes.fill(0)
    }

    fun wipe(chars: CharArray) {
        chars.fill('\u0000')
    }
}
