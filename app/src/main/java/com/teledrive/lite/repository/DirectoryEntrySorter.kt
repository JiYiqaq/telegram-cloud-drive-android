package com.teledrive.lite.repository

import com.teledrive.lite.model.DirectoryEntry
import com.teledrive.lite.model.EntryKind
import com.teledrive.lite.model.SortDirection
import com.teledrive.lite.model.SortMode
import java.util.Locale

object DirectoryEntrySorter {
    fun sort(
        entries: List<DirectoryEntry>,
        mode: SortMode,
        direction: SortDirection,
    ): List<DirectoryEntry> = entries.sortedWith { left, right ->
        val kindComparison = left.kind.order.compareTo(right.kind.order)
        if (kindComparison != 0) {
            kindComparison
        } else {
            val valueComparison = when (mode) {
                SortMode.NAME -> normalized(left.name).compareTo(normalized(right.name))
                SortMode.SIZE -> left.sizeBytes.compareTo(right.sizeBytes)
                SortMode.UPDATED_AT -> left.updatedAtEpochMillis.compareTo(right.updatedAtEpochMillis)
            }
            val directed = if (direction == SortDirection.ASCENDING) {
                valueComparison
            } else {
                -valueComparison
            }
            directed.takeIf { it != 0 }
                ?: normalized(left.name).compareTo(normalized(right.name)).takeIf { it != 0 }
                ?: left.id.compareTo(right.id)
        }
    }

    private val EntryKind.order: Int
        get() = when (this) {
            EntryKind.FOLDER -> 0
            EntryKind.FILE -> 1
        }

    private fun normalized(value: String): String = value.lowercase(Locale.ROOT)
}
