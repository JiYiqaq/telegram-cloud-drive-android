package com.teledrive.lite.crypto

class KeyWrapping(
    private val cryptoEngine: CryptoEngine,
) {
    fun wrap(
        fileId: String,
        masterKey: ByteArray,
        fileDataKey: ByteArray,
    ): ByteArray {
        require(fileId.isNotBlank())
        require(fileDataKey.size == CryptoEngine.DATA_KEY_BYTES)
        return cryptoEngine.encryptChunk(
            key = masterKey,
            plaintext = fileDataKey,
            associatedData = wrappingAssociatedData(fileId),
        )
    }

    fun unwrap(
        fileId: String,
        masterKey: ByteArray,
        wrappedFileDataKey: ByteArray,
    ): ByteArray {
        require(fileId.isNotBlank())
        return cryptoEngine.decryptChunk(
            key = masterKey,
            envelope = wrappedFileDataKey,
            associatedData = wrappingAssociatedData(fileId),
        ).also { fileDataKey ->
            if (fileDataKey.size != CryptoEngine.DATA_KEY_BYTES) {
                fileDataKey.fill(0)
                throw CryptoFormatException()
            }
        }
    }

    private fun wrappingAssociatedData(fileId: String): ByteArray =
        "$WRAPPING_CONTEXT\u0000$fileId".encodeToByteArray()

    private companion object {
        const val WRAPPING_CONTEXT = "teledrive.file-key.v1"
    }
}
