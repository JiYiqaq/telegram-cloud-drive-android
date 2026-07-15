package com.teledrive.lite.telegram

object SecretRedactor {
    private val telegramFileUrl = Regex(
        pattern = """https?://api\.telegram\.org/file/bot[^/\s]+/[^\s]+""",
        option = RegexOption.IGNORE_CASE,
    )
    private val telegramTokenShape = Regex("""\b\d{5,}:AA[A-Za-z0-9_-]{20,}\b""")

    fun redact(text: String?, token: String): String {
        if (text.isNullOrBlank()) return ""
        var result = telegramFileUrl.replace(text, "[REDACTED_URL]")
        if (token.isNotBlank()) {
            result = result.replace(token, "[REDACTED]")
        }
        return telegramTokenShape.replace(result, "[REDACTED]")
    }
}
