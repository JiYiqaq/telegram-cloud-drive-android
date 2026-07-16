package com.teledrive.lite.repository

import com.teledrive.lite.model.DirectoryEntry
import com.teledrive.lite.model.EntryKind
import com.teledrive.lite.model.FileStatus

object DirectoryEntryVisibility {
    fun filter(entries: List<DirectoryEntry>): List<DirectoryEntry> = entries.filterNot { entry ->
        entry.kind == EntryKind.FILE && entry.fileStatus == FileStatus.DELETING
    }
}
