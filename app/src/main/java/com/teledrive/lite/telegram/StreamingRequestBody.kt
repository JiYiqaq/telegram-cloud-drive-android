package com.teledrive.lite.telegram

import java.io.InputStream
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

internal class StreamingRequestBody(
    private val mediaType: MediaType,
    private val declaredLength: Long,
    private val openStream: () -> InputStream,
) : RequestBody() {
    init {
        require(declaredLength >= 0L)
    }

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = declaredLength

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        var copied = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        openStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                copied += read
            }
        }
        check(copied == declaredLength) {
            "Upload source length changed while streaming"
        }
    }
}
