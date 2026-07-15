package com.teledrive.lite.repository

import com.teledrive.lite.model.DirectoryEntry
import com.teledrive.lite.model.EntryKind
import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.SortDirection
import com.teledrive.lite.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryEntrySorterTest {
    private val entries = listOf(
        DirectoryEntry("f-b", "Beta", EntryKind.FOLDER, 0, 30),
        DirectoryEntry("file-z", "zeta.bin", EntryKind.FILE, 10, 20, FileStatus.AVAILABLE),
        DirectoryEntry("f-a", "alpha", EntryKind.FOLDER, 0, 10),
        DirectoryEntry("file-a", "alpha.bin", EntryKind.FILE, 20, 40, FileStatus.AVAILABLE),
    )

    @Test
    fun foldersStayBeforeFilesWhileEachSortModeAndDirectionAreApplied() {
        assertEquals(
            listOf("f-a", "f-b", "file-a", "file-z"),
            DirectoryEntrySorter.sort(entries, SortMode.NAME, SortDirection.ASCENDING).map { it.id },
        )
        assertEquals(
            listOf("f-b", "f-a", "file-a", "file-z"),
            DirectoryEntrySorter.sort(entries, SortMode.UPDATED_AT, SortDirection.DESCENDING).map { it.id },
        )
        assertEquals(
            listOf("f-a", "f-b", "file-z", "file-a"),
            DirectoryEntrySorter.sort(entries, SortMode.SIZE, SortDirection.ASCENDING).map { it.id },
        )
    }
}
