package com.teledrive.lite.telegram

import java.io.IOException

sealed interface TelegramFailure {
    val safeMessage: String

    data class Api(
        val errorCode: Int?,
        val description: String,
        val retryAfterSeconds: Long?,
        val migrateToChatId: Long?,
        val httpStatusCode: Int,
    ) : TelegramFailure {
        override val safeMessage: String = description.ifBlank { "Telegram API request failed" }
    }

    data class Http(
        val statusCode: Int,
    ) : TelegramFailure {
        override val safeMessage: String = "Telegram HTTP error $statusCode"
    }

    data class Network(
        val reason: NetworkReason,
    ) : TelegramFailure {
        override val safeMessage: String = when (reason) {
            NetworkReason.TIMEOUT -> "Telegram network timeout"
            NetworkReason.IO -> "Telegram network request failed"
        }
    }

    data object InvalidResponse : TelegramFailure {
        override val safeMessage: String = "Telegram returned an invalid response"
    }
}

enum class NetworkReason {
    TIMEOUT,
    IO,
}

class TelegramApiException(
    val failure: TelegramFailure,
) : IOException(failure.safeMessage)
