package com.teledrive.lite.sync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
object IndexUpdateJournalCodec {
    fun encode(journal: IndexUpdateJournal): String = json.encodeToString(canonicalize(journal))

    fun decode(encoded: String): IndexUpdateJournal {
        require(encoded.isNotEmpty() && encoded.length <= MAX_JOURNAL_CHARS)
        val decoded = try {
            json.decodeFromString<IndexUpdateJournal>(encoded)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Invalid index-update journal", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid index-update journal", error)
        }
        val canonical = canonicalize(decoded)
        require(encode(canonical) == encoded) { "Index-update journal is not canonical" }
        return canonical
    }

    private fun canonicalize(journal: IndexUpdateJournal): IndexUpdateJournal = journal.copy(
        includedOperationIds = journal.includedOperationIds.toSortedSet(),
    )

    private const val MAX_JOURNAL_CHARS = 1_000_000
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
    }
}
