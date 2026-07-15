package com.teledrive.lite.settings

enum class ConfigValidationError {
    INVALID_BOT_TOKEN,
    INVALID_CHANNEL_ID,
    PASSWORD_REQUIRED,
    PASSWORD_TOO_SHORT,
    PASSWORD_MISMATCH,
}

enum class PasswordStrength {
    WEAK,
    MEDIUM,
    STRONG,
}

data class ValidatedConnectionConfig(
    val botToken: String,
    val channelId: Long,
) {
    override fun toString(): String =
        "ValidatedConnectionConfig(botToken=[REDACTED], channelId=[REDACTED])"
}

data class ConfigValidationResult(
    val config: ValidatedConnectionConfig?,
    val errors: Set<ConfigValidationError>,
    val passwordStrength: PasswordStrength,
) {
    val isValid: Boolean
        get() = config != null && errors.isEmpty()
}

object ConfigValidator {
    const val MIN_PASSWORD_LENGTH: Int = 12

    private val botTokenPattern = Regex(
        pattern = """^\d{5,}:AA[A-Za-z0-9_-]{20,}$""",
    )
    private val privateChannelIdPattern = Regex("""^-100\d+$""")

    fun validate(
        botToken: String,
        channelId: String,
        password: String,
        passwordConfirmation: String,
    ): ConfigValidationResult {
        val normalizedToken = botToken.trim()
        val normalizedChannelId = channelId.trim()
        val parsedChannelId = parsePrivateChannelId(normalizedChannelId)
        val errors = linkedSetOf<ConfigValidationError>()

        if (!isBotTokenValid(normalizedToken)) {
            errors += ConfigValidationError.INVALID_BOT_TOKEN
        }
        if (parsedChannelId == null) {
            errors += ConfigValidationError.INVALID_CHANNEL_ID
        }
        when {
            password.isBlank() -> errors += ConfigValidationError.PASSWORD_REQUIRED
            password.length < MIN_PASSWORD_LENGTH -> {
                errors += ConfigValidationError.PASSWORD_TOO_SHORT
            }
        }
        if (password != passwordConfirmation) {
            errors += ConfigValidationError.PASSWORD_MISMATCH
        }

        val config = if (errors.isEmpty()) {
            ValidatedConnectionConfig(
                botToken = normalizedToken,
                channelId = checkNotNull(parsedChannelId),
            )
        } else {
            null
        }
        return ConfigValidationResult(
            config = config,
            errors = errors,
            passwordStrength = passwordStrength(password),
        )
    }

    fun isBotTokenValid(botToken: String): Boolean = botTokenPattern.matches(botToken.trim())

    fun parsePrivateChannelId(channelId: String): Long? {
        val normalized = channelId.trim()
        val parsed = normalized.toLongOrNull() ?: return null
        return parsed.takeIf { it < 0L && privateChannelIdPattern.matches(normalized) }
    }

    fun passwordStrength(password: String): PasswordStrength {
        if (password.length < MIN_PASSWORD_LENGTH || password.isBlank()) {
            return PasswordStrength.WEAK
        }
        val characterClasses = listOf(
            password.any(Char::isLowerCase),
            password.any(Char::isUpperCase),
            password.any(Char::isDigit),
            password.any { !it.isLetterOrDigit() },
        ).count { it }

        return when {
            password.length >= 16 && characterClasses >= 3 -> PasswordStrength.STRONG
            characterClasses >= 2 -> PasswordStrength.MEDIUM
            else -> PasswordStrength.WEAK
        }
    }
}
