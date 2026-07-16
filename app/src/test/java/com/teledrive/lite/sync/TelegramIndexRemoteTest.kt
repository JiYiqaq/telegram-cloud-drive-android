package com.teledrive.lite.sync

import com.teledrive.lite.telegram.BotIdentity
import com.teledrive.lite.telegram.ChannelUpdate
import com.teledrive.lite.telegram.ChatInfo
import com.teledrive.lite.telegram.ChatMemberInfo
import com.teledrive.lite.telegram.RemoteFile
import com.teledrive.lite.telegram.SentDocument
import com.teledrive.lite.telegram.SentMessage
import com.teledrive.lite.telegram.TelegramDocument
import com.teledrive.lite.telegram.TelegramMessage
import com.teledrive.lite.telegram.TelegramStorageGateway
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TelegramIndexRemoteTest {
    @Test
    fun editsSameMessageAndMapsPinnedDocumentExactly() = runBlocking {
        val gateway = FakeStorageGateway()
        val remote = TelegramIndexRemote(gateway, CHAT_ID)
        val provisional = remote.sendProvisional("op-1")
        val candidate = IndexCandidate(
            revision = 1,
            previousIndexMessageId = null,
            messageId = provisional.messageId,
            fileName = IndexAtomicUpdater.INDEX_FILE_NAME,
            content = byteArrayOf(1, 2, 3),
        )

        val edited = remote.editToFinal(provisional.messageId, candidate)
        gateway.pinnedMessage = TelegramMessage(
            messageId = edited.messageId,
            document = TelegramDocument(
                fileId = edited.fileId,
                fileUniqueId = "unique",
                fileName = edited.fileName,
                mimeType = edited.mimeType,
                size = edited.sizeBytes,
            ),
        )

        assertEquals(edited, remote.getPinned())
        assertEquals(listOf("send:${IndexAtomicUpdater.INDEX_FILE_NAME}", "edit:71"), gateway.calls.take(2))
        assertArrayEquals(byteArrayOf(1, 2, 3), gateway.editedBytes)
    }

    @Test
    fun boundedDownloadRequiresAllTelegramSizesAndByteCountToAgree() = runBlocking {
        val bytes = byteArrayOf(4, 5, 6, 7)
        val gateway = FakeStorageGateway(downloadBytes = bytes)
        val remote = TelegramIndexRemote(gateway, CHAT_ID)
        val document = RemoteIndexDocument(
            messageId = 71,
            fileId = "final-file",
            fileName = IndexAtomicUpdater.INDEX_FILE_NAME,
            sizeBytes = bytes.size.toLong(),
        )

        assertArrayEquals(bytes, remote.download(document))

        gateway.remoteSize = bytes.size + 1L
        val failure = assertThrows(IndexRemoteException::class.java) {
            runBlocking { remote.download(document) }
        }
        assertEquals(IndexRemoteFailure.SIZE_MISMATCH, failure.failure)
    }

    private class FakeStorageGateway(
        private val downloadBytes: ByteArray = byteArrayOf(9),
    ) : TelegramStorageGateway {
        val calls = mutableListOf<String>()
        var pinnedMessage: TelegramMessage? = null
        var editedBytes = byteArrayOf()
        var remoteSize: Long = downloadBytes.size.toLong()

        override suspend fun getMe() = BotIdentity(1, "bot", "bot")
        override suspend fun getChat(chatId: Long) = ChatInfo(chatId, "channel", "drive", pinnedMessage)
        override suspend fun getChatMember(chatId: Long, userId: Long) =
            ChatMemberInfo("administrator", true, true, true)
        override suspend fun getUpdates(offset: Long?): List<ChannelUpdate> = emptyList()
        override suspend fun sendMessage(chatId: Long, text: String) = SentMessage(1)
        override suspend fun deleteMessage(chatId: Long, messageId: Long) = true
        override suspend fun pinChatMessage(chatId: Long, messageId: Long) = true
        override suspend fun unpinChatMessage(chatId: Long, messageId: Long) = true

        override suspend fun sendDocument(
            chatId: Long,
            fileName: String,
            contentLength: Long,
            openStream: () -> InputStream,
        ): SentDocument {
            calls += "send:$fileName"
            openStream().use { require(it.readBytes().size.toLong() == contentLength) }
            return SentDocument(71, "provisional-file", "u1", contentLength)
        }

        override suspend fun editDocument(
            chatId: Long,
            messageId: Long,
            fileName: String,
            contentLength: Long,
            openStream: () -> InputStream,
        ): SentDocument {
            calls += "edit:$messageId"
            editedBytes = openStream().use { it.readBytes() }
            require(editedBytes.size.toLong() == contentLength)
            return SentDocument(messageId, "final-file", "u2", contentLength)
        }

        override suspend fun getFile(fileId: String) = RemoteFile(
            fileId = fileId,
            fileUniqueId = "unique",
            size = remoteSize,
            filePath = "documents/index",
        )

        override suspend fun downloadFile(filePath: String, output: OutputStream): Long {
            ByteArrayInputStream(downloadBytes).use { it.copyTo(output) }
            return downloadBytes.size.toLong()
        }
    }

    private companion object {
        const val CHAT_ID = -1001234567890L
    }
}
