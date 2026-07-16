package com.teledrive.lite.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidatorTest {
    @Test
    fun validInputIsNormalizedWithoutRetainingPassword() {
        val token = dummyToken()
        val password = "Correct-Horse-42!"

        val result = ConfigValidator.validate(
            botToken = "  $token  ",
            channelId = "  -1001234567890  ",
            password = password,
            passwordConfirmation = password,
        )

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
        assertEquals(token, result.config?.botToken)
        assertEquals(-1001234567890L, result.config?.channelId)
        assertEquals(PasswordStrength.STRONG, result.passwordStrength)
        assertFalse(result.toString().contains(token))
        assertFalse(result.toString().contains("-1001234567890"))
        assertFalse(result.toString().contains(password))
    }

    @Test
    fun invalidTokenUsesStructuredErrorWithoutEchoingInput() {
        val invalidToken = "not/a/bot/token"

        val result = validRequest(botToken = invalidToken)

        assertFalse(result.isValid)
        assertNull(result.config)
        assertTrue(result.errors.contains(ConfigValidationError.INVALID_BOT_TOKEN))
        assertFalse(result.toString().contains(invalidToken))
    }

    @Test
    fun channelIdMustBeSignedLongInPrivateChannelForm() {
        listOf(
            "1001234567890",
            "-42",
            "@private_channel",
            "-100999999999999999999999",
        ).forEach { invalidId ->
            val result = validRequest(channelId = invalidId)

            assertFalse(result.isValid)
            assertTrue(result.errors.contains(ConfigValidationError.INVALID_CHANNEL_ID))
            assertFalse(result.toString().contains(invalidId))
        }
    }

    @Test
    fun passwordRequiresEightNonWhitespaceCharacters() {
        val minimumLength = validRequest(password = "Abcd12!x", confirmation = "Abcd12!x")
        val tooShort = validRequest(password = "Abc12!x", confirmation = "Abc12!x")
        val blank = validRequest(password = "            ", confirmation = "            ")

        assertTrue(minimumLength.isValid)
        assertTrue(tooShort.errors.contains(ConfigValidationError.PASSWORD_TOO_SHORT))
        assertTrue(blank.errors.contains(ConfigValidationError.PASSWORD_REQUIRED))
        assertFalse(blank.errors.contains(ConfigValidationError.PASSWORD_TOO_SHORT))
    }

    @Test
    fun passwordConfirmationMustMatchExactly() {
        val result = validRequest(
            password = "Correct-Horse-42!",
            confirmation = "correct-horse-42!",
        )

        assertTrue(result.errors.contains(ConfigValidationError.PASSWORD_MISMATCH))
        assertNull(result.config)
    }

    @Test
    fun passwordStrengthUsesLengthAndCharacterVariety() {
        assertEquals(PasswordStrength.WEAK, ConfigValidator.passwordStrength("abcdefghijkl"))
        assertEquals(PasswordStrength.MEDIUM, ConfigValidator.passwordStrength("abcdefghij12"))
        assertEquals(
            PasswordStrength.STRONG,
            ConfigValidator.passwordStrength("Correct-Horse-42!"),
        )
    }

    private fun validRequest(
        botToken: String = dummyToken(),
        channelId: String = "-1001234567890",
        password: String = "Correct-Horse-42!",
        confirmation: String = password,
    ): ConfigValidationResult = ConfigValidator.validate(
        botToken = botToken,
        channelId = channelId,
        password = password,
        passwordConfirmation = confirmation,
    )

    private fun dummyToken(): String =
        "123456789:" + "AA_TEST_ONLY_abcdefghijklmnopqrstuvwxyz"
}
