package com.teledrive.lite.upload

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream

class ContentResolverUploadInput(
    private val contentResolver: ContentResolver,
) : UploadInput {
    override fun open(sourceUri: String): InputStream = try {
        contentResolver.openInputStream(Uri.parse(sourceUri))
            ?: throw UploadException(UploadFailure.SOURCE_UNAVAILABLE)
    } catch (error: UploadException) {
        throw error
    } catch (error: Exception) {
        throw UploadException(UploadFailure.SOURCE_UNAVAILABLE, error)
    }
}
