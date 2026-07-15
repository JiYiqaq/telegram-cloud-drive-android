package com.teledrive.lite.model

data class FolderNode(
    val id: String,
    val parentId: String?,
)

data class DirectoryEntry(
    val id: String,
    val name: String,
    val kind: EntryKind,
    val sizeBytes: Long,
    val updatedAtEpochMillis: Long,
    val fileStatus: FileStatus? = null,
)

enum class EntryKind {
    FOLDER,
    FILE,
}

data class DirectorySnapshot(
    val folderId: String,
    val breadcrumbs: List<Pair<String, String>>,
    val entries: List<DirectoryEntry>,
)
