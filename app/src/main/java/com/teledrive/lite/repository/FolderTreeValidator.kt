package com.teledrive.lite.repository

import com.teledrive.lite.model.FolderNode

enum class FolderMoveFailure {
    SOURCE_NOT_FOUND,
    TARGET_NOT_FOUND,
    TARGET_IS_SELF,
    TARGET_IS_DESCENDANT,
    ROOT_IS_IMMUTABLE,
    EXISTING_CYCLE,
}

class FolderMoveException(
    val failure: FolderMoveFailure,
) : IllegalArgumentException(failure.name)

object FolderTreeValidator {
    const val ROOT_ID: String = "root"

    fun validateMove(
        sourceFolderId: String,
        targetFolderId: String,
        folders: Collection<FolderNode>,
    ) {
        val byId = folders.toFolderMap()
        if (sourceFolderId == ROOT_ID) fail(FolderMoveFailure.ROOT_IS_IMMUTABLE)
        if (byId[sourceFolderId] == null) fail(FolderMoveFailure.SOURCE_NOT_FOUND)
        if (byId[targetFolderId] == null) fail(FolderMoveFailure.TARGET_NOT_FOUND)
        if (sourceFolderId == targetFolderId) fail(FolderMoveFailure.TARGET_IS_SELF)

        val visited = mutableSetOf<String>()
        var currentId: String? = targetFolderId
        while (currentId != null) {
            if (!visited.add(currentId)) fail(FolderMoveFailure.EXISTING_CYCLE)
            if (currentId == sourceFolderId) fail(FolderMoveFailure.TARGET_IS_DESCENDANT)
            currentId = byId[currentId]?.parentId
        }
    }

    fun pathTo(
        folderId: String,
        folders: Collection<FolderNode>,
    ): List<FolderNode> {
        val byId = folders.toFolderMap()
        if (byId[folderId] == null) fail(FolderMoveFailure.TARGET_NOT_FOUND)
        val reversed = mutableListOf<FolderNode>()
        val visited = mutableSetOf<String>()
        var currentId: String? = folderId
        while (currentId != null) {
            if (!visited.add(currentId)) fail(FolderMoveFailure.EXISTING_CYCLE)
            val node = byId[currentId] ?: fail(FolderMoveFailure.TARGET_NOT_FOUND)
            reversed += node
            currentId = node.parentId
        }
        return reversed.asReversed()
    }

    private fun Collection<FolderNode>.toFolderMap(): Map<String, FolderNode> {
        val byId = associateBy(FolderNode::id)
        if (byId.size != size) fail(FolderMoveFailure.EXISTING_CYCLE)
        return byId
    }

    private fun fail(failure: FolderMoveFailure): Nothing = throw FolderMoveException(failure)
}
