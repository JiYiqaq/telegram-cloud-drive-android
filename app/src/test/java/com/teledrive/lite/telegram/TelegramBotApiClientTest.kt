package com.teledrive.lite.telegram

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TelegramBotApiClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun getMeMapsDomainModelAndKeepsTokenOutOfResult() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"ok":true,"result":{"id":42,"is_bot":true,"first_name":"TeleDrive","username":"teledrive_test_bot"}}""",
            ),
        )

        val result = newClient().getMe()

        assertEquals(42L, result.id)
        assertEquals("TeleDrive", result.displayName)
        assertEquals("teledrive_test_bot", result.username)
        assertFalse(result.toString().contains(TEST_TOKEN))
        assertEquals("/botYOUR_BOT_TOKEN/getMe", server.takeRequest().target)
    }

    @Test
    fun getChatMapsLatestPinnedIndexDocument() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"ok":true,"result":{"id":-1001234567890,"type":"channel","title":"Private Store","pinned_message":{"message_id":88,"date":1700000000,"chat":{"id":-1001234567890,"type":"channel","title":"Private Store"},"document":{"file_name":"teledrive_index_v1.bin","mime_type":"application/octet-stream","file_id":"INDEX_FILE_ID","file_unique_id":"INDEX_UNIQUE","file_size":321}}}}""",
            ),
        )

        val chat = newClient().getChat(CHAT_ID)

        assertEquals(CHAT_ID, chat.id)
        assertEquals("channel", chat.type)
        assertEquals(88L, chat.pinnedMessage?.messageId)
        assertEquals("teledrive_index_v1.bin", chat.pinnedMessage?.document?.fileName)
        assertEquals(321L, chat.pinnedMessage?.document?.size)
        val request = server.takeRequest()
        assertEquals("/botYOUR_BOT_TOKEN/getChat", request.target)
        assertTrue(request.body?.utf8().orEmpty().contains("chat_id=-1001234567890"))
    }

    @Test
    fun getChatMemberMapsRequiredChannelAdministratorRights() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"ok":true,"result":{"status":"administrator","user":{"id":42,"is_bot":true,"first_name":"TeleDrive"},"can_post_messages":true,"can_edit_messages":true,"can_delete_messages":true}}""",
            ),
        )

        val member = newClient().getChatMember(CHAT_ID, 42L)

        assertTrue(member.isAdministrator)
        assertTrue(member.canPostMessages)
        assertTrue(member.canEditMessages)
        assertTrue(member.canDeleteMessages)
        val request = server.takeRequest()
        assertEquals("/botYOUR_BOT_TOKEN/getChatMember", request.target)
        val requestBody = request.body?.utf8().orEmpty()
        assertTrue(requestBody.contains("chat_id=-1001234567890"))
        assertTrue(requestBody.contains("user_id=42"))
    }

    @Test
    fun getUpdatesRequestsAndMapsChannelPostsExplicitly() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"ok":true,"result":[{"update_id":101,"channel_post":{"message_id":7,"date":1700000000,"chat":{"id":-1001234567890,"type":"channel","title":"Private Store"}}}]}""",
            ),
        )

        val updates = newClient().getUpdates(offset = 100L)

        assertEquals(1, updates.size)
        assertEquals(CHAT_ID, updates.single().chatId)
        assertEquals("Private Store", updates.single().chatTitle)
        val requestBody = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(requestBody.contains("channel_post"))
        assertTrue(requestBody.contains("edited_channel_post"))
        assertTrue(requestBody.contains("offset=100"))
    }

    @Test
    fun messageAndPinOperationsUseDedicatedMethodsAndExplicitUnpinId() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"ok":true,"result":{"message_id":51,"date":1700000000,"chat":{"id":-1001234567890,"type":"channel","title":"Private Store"},"text":"temporary"}}""",
            ),
        )
        repeat(3) { server.enqueue(jsonResponse("""{"ok":true,"result":true}""")) }
        val client = newClient()

        assertEquals(51L, client.sendMessage(CHAT_ID, "temporary").messageId)
        assertTrue(client.deleteMessage(CHAT_ID, 51L))
        assertTrue(client.pinChatMessage(CHAT_ID, 88L))
        assertTrue(client.unpinChatMessage(CHAT_ID, 51L))

        assertEquals("/botYOUR_BOT_TOKEN/sendMessage", server.takeRequest().target)
        assertEquals("/botYOUR_BOT_TOKEN/deleteMessage", server.takeRequest().target)
        assertEquals("/botYOUR_BOT_TOKEN/pinChatMessage", server.takeRequest().target)
        val unpin = server.takeRequest()
        assertEquals("/botYOUR_BOT_TOKEN/unpinChatMessage", unpin.target)
        assertTrue(unpin.body?.utf8().orEmpty().contains("message_id=51"))
    }

    @Test
    fun sendDocumentStreamsMultipartAndMapsTelegramIds() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"ok":true,"result":{"message_id":77,"date":1700000000,"chat":{"id":-1001234567890,"type":"channel","title":"Private Store"},"document":{"file_name":"td_123_000001.bin","mime_type":"application/octet-stream","file_id":"REMOTE_FILE_ID","file_unique_id":"REMOTE_UNIQUE","file_size":25}}}""",
            ),
        )
        val payload = "encrypted-chunk".encodeToByteArray()

        val result = newClient().sendDocument(
            chatId = CHAT_ID,
            fileName = "td_123_000001.bin",
            contentLength = payload.size.toLong(),
            openStream = { ByteArrayInputStream(payload) },
        )

        assertEquals(77L, result.messageId)
        assertEquals("REMOTE_FILE_ID", result.fileId)
        val request = server.takeRequest()
        assertEquals("/botYOUR_BOT_TOKEN/sendDocument", request.target)
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("multipart/form-data"))
        val multipart = request.body?.utf8().orEmpty()
        assertTrue(multipart.contains("td_123_000001.bin"))
        assertTrue(multipart.contains("encrypted-chunk"))
    }

    @Test
    fun getFileThenDownloadStreamsWithoutExposingPermanentUrl() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"ok":true,"result":{"file_id":"REMOTE_FILE_ID","file_unique_id":"REMOTE_UNIQUE","file_size":4,"file_path":"documents/encrypted.bin"}}""",
            ),
        )
        server.enqueue(MockResponse(body = "DATA"))
        val output = ByteArrayOutputStream()
        val client = newClient()

        val remote = client.getFile("REMOTE_FILE_ID")
        val copied = client.downloadFile(remote.filePath, output)

        assertEquals(4L, remote.size)
        assertEquals(4L, copied)
        assertEquals("DATA", output.toString(Charsets.UTF_8.name()))
        assertEquals("/botYOUR_BOT_TOKEN/getFile", server.takeRequest().target)
        assertEquals("/file/botYOUR_BOT_TOKEN/documents/encrypted.bin", server.takeRequest().target)
        assertFalse(remote.toString().contains("api.telegram.org"))
    }

    @Test
    fun okFalseAndPlainHttpErrorsUseUnifiedFailures() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 401,
                body = """{"ok":false,"error_code":401,"description":"Unauthorized"}""",
            ),
        )
        server.enqueue(MockResponse(code = 503, body = "upstream unavailable"))
        val client = newClient()

        val apiError = captureFailure { client.getMe() }
        assertEquals(401, (apiError.failure as TelegramFailure.Api).errorCode)
        val httpError = captureFailure { client.getMe() }
        assertEquals(503, (httpError.failure as TelegramFailure.Http).statusCode)
    }

    @Test
    fun apiErrorDescriptionIsRedactedAfterRealResponseParsing() = runBlocking {
        val downloadUrl =
            "https://api.telegram.org/file/bot$TEST_TOKEN/documents/encrypted.bin"
        server.enqueue(
            MockResponse(
                code = 400,
                body =
                    """{"ok":false,"error_code":400,"description":"leaked $TEST_TOKEN at $downloadUrl"}""",
            ),
        )

        val error = captureFailure { newClient().getMe() }
        val failure = error.failure as TelegramFailure.Api

        assertFalse(error.message.orEmpty().contains(TEST_TOKEN))
        assertFalse(error.message.orEmpty().contains("api.telegram.org/file"))
        assertFalse(failure.description.contains(TEST_TOKEN))
        assertFalse(failure.description.contains("api.telegram.org/file"))
        assertTrue(failure.description.contains("[REDACTED]"))
    }

    @Test
    fun interruptedDownloadDoesNotRetryIntoSameOutputStream() = runBlocking {
        server.enqueue(MockResponse(body = "DATA"))
        server.enqueue(MockResponse(body = "DATA"))
        val output = FailOnceOutputStream(bytesBeforeFailure = 2)

        val error = captureFailure {
            newClient().downloadFile("documents/encrypted.bin", output)
        }

        assertTrue(error.failure is TelegramFailure.Network)
        assertEquals(1, server.requestCount)
        assertEquals("DA", output.contents())
    }

    @Test
    fun retryAfterWaitsBeforeRetryingOnlyIdempotentRequest() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 429,
                body = """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":12}}""",
            ),
        )
        server.enqueue(
            jsonResponse(
                """{"ok":true,"result":{"id":42,"is_bot":true,"first_name":"TeleDrive"}}""",
            ),
        )
        val delay = RecordingRetryDelay()

        val result = newClient(delay = delay).getMe()

        assertEquals(42L, result.id)
        assertEquals(listOf(12L), delay.waitedSeconds)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun nonIdempotentUploadDoesNotRetryAfterRateLimit() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 429,
                body = """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":3}}""",
            ),
        )
        val client = newClient(delay = RecordingRetryDelay())

        val error = captureFailure {
            client.sendDocument(CHAT_ID, "td_123_000001.bin", 1L) {
                ByteArrayInputStream(byteArrayOf(1))
            }
        }

        assertEquals(3L, (error.failure as TelegramFailure.Api).retryAfterSeconds)
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
    }

    @Test
    fun timeoutBecomesSanitizedNetworkFailure() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .headersDelay(500, TimeUnit.MILLISECONDS)
                .body("""{"ok":true,"result":{"id":42,"is_bot":true,"first_name":"TeleDrive"}}""")
                .build(),
        )
        val shortClient = OkHttpClient.Builder()
            .callTimeout(50, TimeUnit.MILLISECONDS)
            .readTimeout(50, TimeUnit.MILLISECONDS)
            .build()

        val error = captureFailure { newClient(httpClient = shortClient).getMe() }

        assertTrue(error.failure is TelegramFailure.Network)
        assertFalse(error.message.orEmpty().contains(TEST_TOKEN))
    }

    private fun newClient(
        httpClient: OkHttpClient = OkHttpClient(),
        delay: RetryDelay = RecordingRetryDelay(),
    ): TelegramBotApiClient = TelegramBotApiClient(
        token = TEST_TOKEN,
        httpClient = httpClient,
        apiBaseUrl = server.url("/"),
        fileBaseUrl = server.url("/file/"),
        retryPolicy = RetryPolicy(maxIdempotentAttempts = 2, delay = delay),
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse(
        headers = okhttp3.Headers.headersOf("Content-Type", "application/json"),
        body = body,
    )

    private suspend fun captureFailure(block: suspend () -> Unit): TelegramApiException = try {
        block()
        fail("Expected TelegramApiException")
        error("unreachable")
    } catch (error: TelegramApiException) {
        error
    }

    private class RecordingRetryDelay : RetryDelay {
        val waitedSeconds = mutableListOf<Long>()

        override suspend fun await(seconds: Long) {
            waitedSeconds += seconds
        }
    }

    private class FailOnceOutputStream(
        private val bytesBeforeFailure: Int,
    ) : OutputStream() {
        private val delegate = ByteArrayOutputStream()
        private var shouldFail = true

        override fun write(value: Int) {
            delegate.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (shouldFail) {
                delegate.write(bytes, offset, bytesBeforeFailure.coerceAtMost(length))
                shouldFail = false
                throw IOException("simulated interrupted destination")
            }
            delegate.write(bytes, offset, length)
        }

        fun contents(): String = delegate.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val TEST_TOKEN = "YOUR_BOT_TOKEN"
        const val CHAT_ID = -1001234567890L
    }
}
