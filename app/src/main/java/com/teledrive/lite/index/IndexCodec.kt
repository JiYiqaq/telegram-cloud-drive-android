package com.teledrive.lite.index

import java.nio.charset.CharacterCodingException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
object IndexCodec {
    fun encode(payload: CloudIndexPayload): ByteArray {
        IndexValidator.requireValid(payload)
        return encodeCanonical(IndexValidator.canonicalize(payload))
    }

    fun decode(bytes: ByteArray): CloudIndexPayload {
        if (bytes.isEmpty() || bytes.size > MAX_PAYLOAD_BYTES) malformed()
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            malformed()
        }
        val decoded = try {
            json.decodeFromString<CloudIndexPayload>(text)
        } catch (_: SerializationException) {
            malformed()
        } catch (_: IllegalArgumentException) {
            malformed()
        }
        IndexValidator.requireValid(decoded)
        val canonical = IndexValidator.canonicalize(decoded)
        if (!bytes.contentEquals(encodeCanonical(canonical))) {
            throw IndexFormatException(IndexFormatFailure.NON_CANONICAL)
        }
        return canonical
    }

    private fun encodeCanonical(payload: CloudIndexPayload): ByteArray =
        json.encodeToString(payload).encodeToByteArray()

    private fun malformed(): Nothing =
        throw IndexFormatException(IndexFormatFailure.MALFORMED)

    private const val MAX_PAYLOAD_BYTES = 20_000_000

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
        allowSpecialFloatingPointValues = false
    }
}
