package com.teledrive.lite.repository

import com.teledrive.lite.model.DirectoryEntry
import com.teledrive.lite.model.EntryKind
import com.teledrive.lite.model.FileStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryEntryVisibilityTest {
    @Test
    fun activeDeletionIsHiddenWhileRecoverableFailureRemainsVisible() {
        val entries = listOf(
            entry("folder", EntryKind.FOLDER),
            entry("available", EntryKind.FILE, FileStatus.AVAILABLE),
            entry("deleting", EntryKind.FILE, FileStatus.DELETING),
            entry("partial", EntryKind.FILE, FileStatus.PARTIALLY_DELETED),
        )

        val visibleIds = DirectoryEntryVisibility.filter(entries).map(DirectoryEntry::id)

        assertEquals(listOf("folder", "available", "partial"), visibleIds)
    }

    private fun entry(
        id: String,
        kind: EntryKind,
        status: FileStatus? = null,
    ): DirectoryEntry = DirectoryEntry(
        id = id,
        name = id,
        kind = kind,
        sizeBytes = 1,
        updatedAtEpochMillis = 1,
        fileStatus = status,
    )
}
