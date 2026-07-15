package com.teledrive.lite.settings

interface SecretCipher {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray

    fun deleteKey()
}
