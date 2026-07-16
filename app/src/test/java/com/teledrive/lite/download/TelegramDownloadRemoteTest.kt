package com.teledrive.lite.download

import com.teledrive.lite.telegram.BotIdentity
import com.teledrive.lite.telegram.ChannelUpdate
import com.teledrive.lite.telegram.ChatInfo
import com.teledrive.lite.telegram.ChatMemberInfo
import com.teledrive.lite.telegram.RemoteFile
import com.teledrive.lite.telegram.SentDocument
import com.teledrive.lite.telegram.SentMessage
import com.teledrive.lite.telegram.TelegramStorageGateway
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TelegramDownloadRemoteTest {
    @Test
    fun validatesGetFileAndActualStreamLength() = runBlocking {
        val bytes = ByteArray(38) { it.toByte() }
        val gateway = FakeGateway(bytes)

        val result = TelegramDownloadRemote(gateway).downloadChunk("remote", 38)

        assertArrayEquals(bytes, result)
        assertEquals("remote", gateway.requestedFileId)
        assertEquals("documents/encrypted.bin", gateway.requestedPath)
    }

    @Test
    fun rejectsMismatchedGetFileMetadataBeforeDownloading() = runBlocking {
        val gateway = FakeGateway(ByteArray(38)).apply {
            remoteFile = RemoteFile("other", null, 38, "documents/encrypted.bin")
        }

        val failure = assertThrows(DownloadException::class.java) {
            runBlocking { TelegramDownloadRemote(gateway).downloadChunk("remote", 38) }
        }

        assertEquals(DownloadFailure.REMOTE_SIZE_MISMATCH, failure.failure)
        assertEquals(null, gateway.requestedPath)
    }

    @Test
    fun boundedOutputRejectsBodyLargerThanIndexMetadata() = runBlocking {
        val gateway = FakeGateway(ByteArray(39))

        val failure = assertThrows(DownloadException::class.java) {
            runBlocking { TelegramDownloadRemote(gateway).downloadChunk("remote", 38) }
        }

        assertEquals(DownloadFailure.REMOTE_SIZE_MISMATCH, failure.failure)
    }

    private class FakeGateway(private val bytes: ByteArray) : TelegramStorageGateway {
        var remoteFile = RemoteFile("remote", null, bytes.size.toLong(), "documents/encrypted.bin")
        var requestedFileId: String? = null
        var requestedPath: String? = null

        override suspend fun getFile(fileId: String): RemoteFile {
            requestedFileId = fileId
            return remoteFile
        }

        override suspend fun downloadFile(filePath: String, output: OutputStream): Long {
            requestedPath = filePath
            output.write(bytes)
            return bytes.size.toLong()
        }

        override suspend fun getMe() = BotIdentity(1, "bot", "bot")
        override suspend fun getChat(chatId: Long) = ChatInfo(chatId, "channel", "test", null)
        override suspend fun getChatMember(chatId: Long, userId: Long) =
            ChatMemberInfo("administrator", true, true, true)
        override suspend fun getUpdates(offset: Long?): List<ChannelUpdate> = emptyList()
        override suspend fun sendMessage(chatId: Long, text: String) = SentMessage(1)
        override suspend fun deleteMessage(chatId: Long, messageId: Long) = true
        override suspend fun sendDocument(
            chatId: Long,
            fileName: String,
            contentLength: Long,
            openStream: () -> InputStream,
        ) = SentDocument(1, "file", null, contentLength)
        override suspend fun editDocument(
            chatId: Long,
            messageId: Long,
            fileName: String,
            contentLength: Long,
            openStream: () -> InputStream,
        ) = SentDocument(messageId, "file", null, contentLength)
        override suspend fun pinChatMessage(chatId: Long, messageId: Long) = true
        override suspend fun unpinChatMessage(chatId: Long, messageId: Long) = true
    }
}
