package com.teledrive.lite.repository

import com.teledrive.lite.model.FolderNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FolderTreeValidatorTest {
    private val folders = listOf(
        FolderNode(FolderTreeValidator.ROOT_ID, null),
        FolderNode("a", FolderTreeValidator.ROOT_ID),
        FolderNode("a-child", "a"),
        FolderNode("b", FolderTreeValidator.ROOT_ID),
    )

    @Test
    fun movingAcrossBranchesAndBuildingBreadcrumbAreValid() {
        FolderTreeValidator.validateMove("a-child", "b", folders)

        assertEquals(
            listOf(FolderTreeValidator.ROOT_ID, "a", "a-child"),
            FolderTreeValidator.pathTo("a-child", folders).map(FolderNode::id),
        )
    }

    @Test
    fun selfAndDescendantMovesAreRejected() {
        assertFailure(FolderMoveFailure.TARGET_IS_SELF) {
            FolderTreeValidator.validateMove("a", "a", folders)
        }
        assertFailure(FolderMoveFailure.TARGET_IS_DESCENDANT) {
            FolderTreeValidator.validateMove("a", "a-child", folders)
        }
    }

    @Test
    fun missingSourceOrTargetAndRootMoveAreRejected() {
        assertFailure(FolderMoveFailure.SOURCE_NOT_FOUND) {
            FolderTreeValidator.validateMove("missing", "b", folders)
        }
        assertFailure(FolderMoveFailure.TARGET_NOT_FOUND) {
            FolderTreeValidator.validateMove("a", "missing", folders)
        }
        assertFailure(FolderMoveFailure.ROOT_IS_IMMUTABLE) {
            FolderTreeValidator.validateMove(FolderTreeValidator.ROOT_ID, "b", folders)
        }
    }

    @Test
    fun preExistingCycleIsDetectedInsteadOfLoopingForever() {
        val cyclic = folders + listOf(
            FolderNode("x", "y"),
            FolderNode("y", "x"),
        )

        assertFailure(FolderMoveFailure.EXISTING_CYCLE) {
            FolderTreeValidator.validateMove("a", "x", cyclic)
        }
        assertFailure(FolderMoveFailure.EXISTING_CYCLE) {
            FolderTreeValidator.pathTo("x", cyclic)
        }
    }

    private fun assertFailure(
        expected: FolderMoveFailure,
        block: () -> Unit,
    ) {
        val error = assertThrows(FolderMoveException::class.java, block)
        assertEquals(expected, error.failure)
    }
}
