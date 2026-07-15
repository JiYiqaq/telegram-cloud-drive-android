package com.teledrive.lite.telegram

import kotlinx.coroutines.delay

fun interface RetryDelay {
    suspend fun await(seconds: Long)
}

object CoroutineRetryDelay : RetryDelay {
    override suspend fun await(seconds: Long) {
        delay(seconds.coerceAtLeast(0L) * 1_000L)
    }
}

data class RetryPolicy(
    val maxIdempotentAttempts: Int = 2,
    val delay: RetryDelay = CoroutineRetryDelay,
) {
    init {
        require(maxIdempotentAttempts >= 1)
    }

    fun delayBeforeRetrySeconds(
        completedAttempts: Int,
        idempotent: Boolean,
        failure: TelegramFailure,
    ): Long? {
        if (!idempotent || completedAttempts >= maxIdempotentAttempts) return null
        return when (failure) {
            is TelegramFailure.Api -> failure.retryAfterSeconds?.coerceAtLeast(0L)
            is TelegramFailure.Network -> 1L
            else -> null
        }
    }
}
