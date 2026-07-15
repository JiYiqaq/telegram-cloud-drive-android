package com.teledrive.lite.transfer

/**
 * A plaintext chunk whose [bytes] are valid only for the duration of the
 * synchronous consumer callback passed to [StreamingChunker.forEachChunk].
 */
data class PlainChunk(
    val index: Int,
    val bytes: ByteArray,
)
