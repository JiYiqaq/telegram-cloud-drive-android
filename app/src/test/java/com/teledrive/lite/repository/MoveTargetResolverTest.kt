package com.teledrive.lite.repository

import com.teledrive.lite.model.FolderDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

class MoveTargetResolverTest {
    private val folders = listOf(
        FolderDescriptor(FolderTreeValidator.ROOT_ID, "我的云盘", null),
        FolderDescriptor("a", "A", FolderTreeValidator.ROOT_ID),
        FolderDescriptor("a-child", "子目录", "a"),
        FolderDescriptor("b", "B", FolderTreeValidator.ROOT_ID),
    )

    @Test
    fun fileOnlySelectionCanMoveToEveryFolderWithFullPaths() {
        val targets = MoveTargetResolver.resolve(folders, emptySet())

        assertEquals(
            listOf(
                FolderTreeValidator.ROOT_ID to "我的云盘",
                "a" to "我的云盘 / A",
                "a-child" to "我的云盘 / A / 子目录",
                "b" to "我的云盘 / B",
            ),
            targets.map { it.id to it.path },
        )
    }

    @Test
    fun selectedFolderCannotMoveIntoItselfOrItsDescendant() {
        val targets = MoveTargetResolver.resolve(folders, setOf("a"))

        assertEquals(
            listOf(FolderTreeValidator.ROOT_ID, "b"),
            targets.map { it.id },
        )
    }

    @Test
    fun targetsMustBeValidForEverySelectedFolder() {
        val targets = MoveTargetResolver.resolve(folders, setOf("a-child", "b"))

        assertEquals(
            listOf(FolderTreeValidator.ROOT_ID, "a"),
            targets.map { it.id },
        )
    }
}
