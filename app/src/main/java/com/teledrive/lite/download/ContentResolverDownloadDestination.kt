package com.teledrive.lite.download

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import java.io.OutputStream

class ContentResolverDownloadDestination(
    private val contentResolver: ContentResolver,
) : DownloadDestination {
    override fun open(destinationUri: String): DownloadOutput {
        val uri = destinationUri.toUri()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw DownloadException(DownloadFailure.DESTINATION_UNAVAILABLE)
        }
        val stream = try {
            contentResolver.openOutputStream(uri, "wt")
        } catch (error: Exception) {
            throw DownloadException(DownloadFailure.DESTINATION_UNAVAILABLE, error)
        } ?: throw DownloadException(DownloadFailure.DESTINATION_UNAVAILABLE)
        return ResolverDownloadOutput(contentResolver, uri, stream)
    }
}

private class ResolverDownloadOutput(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val stream: OutputStream,
) : DownloadOutput {
    private var closed = false

    override fun write(plaintext: ByteArray) {
        check(!closed)
        stream.write(plaintext)
    }

    override fun commit() {
        check(!closed)
        stream.flush()
        stream.close()
        closed = true
    }

    override fun abort(removeDestination: Boolean) {
        if (!closed) {
            runCatching { stream.close() }
            closed = true
        }
        if (removeDestination) {
            val deleted = runCatching { contentResolver.delete(uri, null, null) }.getOrDefault(0)
            if (deleted > 0) return
        }
        contentResolver.openOutputStream(uri, "wt")?.use { it.flush() }
    }
}
