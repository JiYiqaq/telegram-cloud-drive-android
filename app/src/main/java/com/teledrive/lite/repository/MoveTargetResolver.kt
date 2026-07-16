package com.teledrive.lite.repository

import com.teledrive.lite.model.FolderDescriptor
import com.teledrive.lite.model.FolderNode
import com.teledrive.lite.model.MoveTarget

object MoveTargetResolver {
    fun resolve(
        folders: Collection<FolderDescriptor>,
        selectedFolderIds: Set<String>,
    ): List<MoveTarget> {
        val nodes = folders.map { FolderNode(it.id, it.parentId) }
        val names = folders.associate { it.id to it.name }
        return folders.asSequence()
            .filter { target ->
                selectedFolderIds.all { sourceId ->
                    runCatching {
                        FolderTreeValidator.validateMove(sourceId, target.id, nodes)
                    }.isSuccess
                }
            }
            .map { folder ->
                val path = FolderTreeValidator.pathTo(folder.id, nodes)
                    .joinToString(" / ") { node -> checkNotNull(names[node.id]) }
                MoveTarget(folder.id, path)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, MoveTarget::path))
            .toList()
    }
}
