/*
 * Initial implementation created with OpenAI Codex
 * based on requirements provided by the project maintainer.
 */

package com.teledrive.lite.telegram

import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class TelegramBotApiClient(
    private val token: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val apiBaseUrl: HttpUrl = DEFAULT_API_BASE_URL,
    private val fileBaseUrl: HttpUrl = DEFAULT_FILE_BASE_URL,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) : TelegramStorageGateway {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    init {
        require(token.isNotBlank())
    }

    override suspend fun getMe(): BotIdentity {
        val user = executeMethod<TelegramUserDto>(
            method = "getMe",
            body = emptyForm(),
            idempotent = true,
        )
        return BotIdentity(
            id = user.id,
            displayName = listOfNotNull(user.firstName, user.lastName).joinToString(" "),
            username = user.username,
        )
    }

    override suspend fun getChat(chatId: Long): ChatInfo {
        val chat = executeMethod<TelegramChatFullDto>(
            method = "getChat",
            body = form("chat_id" to chatId.toString()),
            idempotent = true,
        )
        return ChatInfo(
            id = chat.id,
            type = chat.type,
            title = chat.title,
            pinnedMessage = chat.pinnedMessage?.toDomain(),
            username = chat.username,
        )
    }

    override suspend fun getChatMember(chatId: Long, userId: Long): ChatMemberInfo {
        val member = executeMethod<TelegramChatMemberDto>(
            method = "getChatMember",
            body = form("chat_id" to chatId.toString(), "user_id" to userId.toString()),
            idempotent = true,
        )
        return ChatMemberInfo(
            status = member.status,
            canPostMessages = member.canPostMessages,
            canEditMessages = member.canEditMessages,
            canDeleteMessages = member.canDeleteMessages,
        )
    }

    override suspend fun getUpdates(offset: Long?): List<ChannelUpdate> {
        val builder = FormBody.Builder()
            .add(
                "allowed_updates",
                json.encodeToString(listOf("channel_post", "edited_channel_post")),
            )
        offset?.let { builder.add("offset", it.toString()) }
        val updates = executeMethod<List<TelegramUpdateDto>>(
            method = "getUpdates",
            body = builder.build(),
            idempotent = true,
        )
        return updates.mapNotNull { update ->
            val post = update.channelPost ?: update.editedChannelPost ?: return@mapNotNull null
            if (post.chat.type != "channel") return@mapNotNull null
            ChannelUpdate(
                updateId = update.updateId,
                messageId = post.messageId,
                chatId = post.chat.id,
                chatTitle = post.chat.title,
                chatUsername = post.chat.username,
            )
        }
    }

    override suspend fun sendMessage(chatId: Long, text: String): SentMessage {
        val message = executeMethod<TelegramMessageDto>(
            method = "sendMessage",
            body = form("chat_id" to chatId.toString(), "text" to text),
            idempotent = false,
        )
        return SentMessage(messageId = message.messageId)
    }

    override suspend fun deleteMessage(chatId: Long, messageId: Long): Boolean =
        executeMethod(
            method = "deleteMessage",
            body = form("chat_id" to chatId.toString(), "message_id" to messageId.toString()),
            idempotent = false,
        )

    override suspend fun pinChatMessage(chatId: Long, messageId: Long): Boolean =
        executeMethod(
            method = "pinChatMessage",
            body = form("chat_id" to chatId.toString(), "message_id" to messageId.toString()),
            idempotent = false,
        )

    override suspend fun unpinChatMessage(chatId: Long, messageId: Long): Boolean =
        executeMethod(
            method = "unpinChatMessage",
            body = form("chat_id" to chatId.toString(), "message_id" to messageId.toString()),
            idempotent = false,
        )

    override suspend fun sendDocument(
        chatId: Long,
        fileName: String,
        contentLength: Long,
        openStream: () -> java.io.InputStream,
    ): SentDocument {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName)
        require(
            TelegramCloudLimits.isEncryptedChunkSizeSafe(contentLength),
        ) { "Encrypted chunk exceeds the cloud download safety boundary" }
        val requestBody = StreamingRequestBody(
            mediaType = OCTET_STREAM,
            declaredLength = contentLength,
            openStream = openStream,
        )
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("document", fileName, requestBody)
            .build()
        val message = executeMethod<TelegramMessageDto>(
            method = "sendDocument",
            body = multipart,
            idempotent = false,
        )
        val document = message.document ?: throw TelegramApiException(TelegramFailure.InvalidResponse)
        return SentDocument(
            messageId = message.messageId,
            fileId = document.fileId,
            fileUniqueId = document.fileUniqueId,
            size = document.fileSize,
        )
    }

    override suspend fun editDocument(
        chatId: Long,
        messageId: Long,
        fileName: String,
        contentLength: Long,
        openStream: () -> java.io.InputStream,
    ): SentDocument {
        require(messageId > 0)
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName)
        require(
            TelegramCloudLimits.isEncryptedChunkSizeSafe(contentLength),
        ) { "Encrypted document exceeds the cloud download safety boundary" }
        val requestBody = StreamingRequestBody(
            mediaType = OCTET_STREAM,
            declaredLength = contentLength,
            openStream = openStream,
        )
        val media = buildJsonObject {
            put("type", "document")
            put("media", "attach://document")
            put("disable_content_type_detection", true)
        }.toString()
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("message_id", messageId.toString())
            .addFormDataPart("media", media)
            .addFormDataPart("document", fileName, requestBody)
            .build()
        val message = executeMethod<TelegramMessageDto>(
            method = "editMessageMedia",
            body = multipart,
            idempotent = false,
        )
        val document = message.document
            ?: throw TelegramApiException(TelegramFailure.InvalidResponse)
        if (message.messageId != messageId) {
            throw TelegramApiException(TelegramFailure.InvalidResponse)
        }
        return SentDocument(
            messageId = message.messageId,
            fileId = document.fileId,
            fileUniqueId = document.fileUniqueId,
            size = document.fileSize,
        )
    }

    override suspend fun getFile(fileId: String): RemoteFile {
        val file = executeMethod<TelegramFileDto>(
            method = "getFile",
            body = form("file_id" to fileId),
            idempotent = true,
        )
        val path = file.filePath?.takeIf { it.isSafeFilePath() }
            ?: throw TelegramApiException(TelegramFailure.InvalidResponse)
        return RemoteFile(
            fileId = file.fileId,
            fileUniqueId = file.fileUniqueId,
            size = file.fileSize,
            filePath = path,
        )
    }

    override suspend fun downloadFile(filePath: String, output: OutputStream): Long {
        require(filePath.isSafeFilePath())
        val urlBuilder = fileBaseUrl.newBuilder().addPathSegment("bot$token")
        filePath.split('/').forEach(urlBuilder::addPathSegment)
        val request = Request.Builder().url(urlBuilder.build()).get().build()
        return executeWithRetry(idempotent = false) {
            executeDownloadRequest(request, output)
        }
    }

    private suspend inline fun <reified T> executeMethod(
        method: String,
        body: RequestBody,
        idempotent: Boolean,
    ): T {
        val request = Request.Builder()
            .url(apiMethodUrl(method))
            .post(body)
            .build()
        return executeWithRetry(idempotent) {
            executeJsonRequest<T>(request)
        }
    }

    private suspend fun <T> executeWithRetry(
        idempotent: Boolean,
        block: suspend () -> T,
    ): T {
        var completedAttempts = 0
        while (true) {
            try {
                return block()
            } catch (error: TelegramApiException) {
                completedAttempts += 1
                val waitSeconds = retryPolicy.delayBeforeRetrySeconds(
                    completedAttempts = completedAttempts,
                    idempotent = idempotent,
                    failure = error.failure,
                ) ?: throw error
                retryPolicy.delay.await(waitSeconds)
            }
        }
    }

    private suspend inline fun <reified T> executeJsonRequest(request: Request): T =
        withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    val responseText = response.body.string()
                    val envelope = try {
                        json.decodeFromString<TelegramResponseDto<T>>(responseText)
                    } catch (_: Exception) {
                        if (!response.isSuccessful) {
                            throw TelegramApiException(TelegramFailure.Http(response.code))
                        }
                        throw TelegramApiException(TelegramFailure.InvalidResponse)
                    }
                    if (!envelope.ok) {
                        throw TelegramApiException(
                            TelegramFailure.Api(
                                errorCode = envelope.errorCode,
                                description = SecretRedactor.redact(envelope.description, token),
                                retryAfterSeconds = envelope.parameters?.retryAfter,
                                migrateToChatId = envelope.parameters?.migrateToChatId,
                                httpStatusCode = response.code,
                            ),
                        )
                    }
                    if (!response.isSuccessful) {
                        throw TelegramApiException(TelegramFailure.Http(response.code))
                    }
                    envelope.result ?: throw TelegramApiException(TelegramFailure.InvalidResponse)
                }
            } catch (error: TelegramApiException) {
                throw error
            } catch (_: SocketTimeoutException) {
                throw TelegramApiException(TelegramFailure.Network(NetworkReason.TIMEOUT))
            } catch (_: InterruptedIOException) {
                throw TelegramApiException(TelegramFailure.Network(NetworkReason.TIMEOUT))
            } catch (_: java.io.IOException) {
                throw TelegramApiException(TelegramFailure.Network(NetworkReason.IO))
            }
        }

    private suspend fun executeDownloadRequest(request: Request, output: OutputStream): Long =
        withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw TelegramApiException(TelegramFailure.Http(response.code))
                    }
                    var total = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    response.body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            total += read
                        }
                    }
                    total
                }
            } catch (error: TelegramApiException) {
                throw error
            } catch (_: SocketTimeoutException) {
                throw TelegramApiException(TelegramFailure.Network(NetworkReason.TIMEOUT))
            } catch (_: InterruptedIOException) {
                throw TelegramApiException(TelegramFailure.Network(NetworkReason.TIMEOUT))
            } catch (_: java.io.IOException) {
                throw TelegramApiException(TelegramFailure.Network(NetworkReason.IO))
            }
        }

    private fun apiMethodUrl(method: String): HttpUrl = apiBaseUrl.newBuilder()
        .addPathSegment("bot$token")
        .addPathSegment(method)
        .build()

    private fun emptyForm(): FormBody = FormBody.Builder().build()

    private fun form(vararg fields: Pair<String, String>): FormBody = FormBody.Builder().apply {
        fields.forEach { (name, value) -> add(name, value) }
    }.build()

    private fun TelegramMessageDto.toDomain(): TelegramMessage = TelegramMessage(
        messageId = messageId,
        document = document?.let {
            TelegramDocument(
                fileId = it.fileId,
                fileUniqueId = it.fileUniqueId,
                fileName = it.fileName,
                mimeType = it.mimeType,
                size = it.fileSize,
            )
        },
    )

    private fun String.isSafeFilePath(): Boolean =
        isNotBlank() && !startsWith('/') && !contains("..") && !contains("://")

    companion object {
        private val DEFAULT_API_BASE_URL = "https://api.telegram.org/".toHttpUrl()
        private val DEFAULT_FILE_BASE_URL = "https://api.telegram.org/file/".toHttpUrl()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
