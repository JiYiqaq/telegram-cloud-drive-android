package com.teledrive.lite.repository

import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRulesTest {
    @Test
    fun requiredFileAndTransferStatesArePresent() {
        assertEquals(
            setOf(
                "PENDING", "ENCRYPTING", "UPLOADING", "AVAILABLE", "DOWNLOADING",
                "FAILED", "DELETING", "PARTIALLY_DELETED", "CORRUPTED",
            ),
            FileStatus.entries.map { it.name }.toSet(),
        )
        assertEquals(
            setOf(
                "QUEUED", "RUNNING", "PAUSED", "WAITING_FOR_NETWORK",
                "WAITING_FOR_RETRY", "SUCCESS", "FAILED", "CANCELED",
            ),
            TransferStatus.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun driveNamesRejectTraversalSeparatorsControlsAndOversizeValues() {
        listOf("", " ", ".", "..", "a/b", "a\\b", "bad\u0000name", "x".repeat(256)).forEach {
            assertFalse("Expected invalid name: $it", DriveNameValidator.isValid(it))
        }
        assertTrue(DriveNameValidator.isValid("学习资料 2026.pdf"))
    }

    @Test
    fun sqliteNoCaseKeyOnlyFoldsAsciiLetters() {
        assertEquals("report.txt", DriveNameEquivalence.sqliteNoCaseKey("REPORT.txt"))
        assertEquals("Ä", DriveNameEquivalence.sqliteNoCaseKey("Ä"))
        assertEquals("ä", DriveNameEquivalence.sqliteNoCaseKey("ä"))
    }

    @Test
    fun dirtyIndexAlwaysProtectsNewerLocalRecoveryState() {
        assertTrue(
            CloudCacheReplacementPolicy.mustPreserveLocalState(
                hasLocalOnlyFiles = false,
                currentIndexSyncStatus = IndexSyncStatus.DIRTY,
                hasUncoveredPendingOperations = false,
            ),
        )
        listOf(IndexSyncStatus.SYNCING, IndexSyncStatus.FAILED).forEach { status ->
            assertTrue(
                CloudCacheReplacementPolicy.mustPreserveLocalState(
                    hasLocalOnlyFiles = false,
                    currentIndexSyncStatus = status,
                    hasUncoveredPendingOperations = false,
                ),
            )
        }
        assertTrue(
            CloudCacheReplacementPolicy.mustPreserveLocalState(
                hasLocalOnlyFiles = false,
                currentIndexSyncStatus = IndexSyncStatus.SYNCED,
                hasUncoveredPendingOperations = true,
            ),
        )
        assertFalse(
            CloudCacheReplacementPolicy.mustPreserveLocalState(
                hasLocalOnlyFiles = false,
                currentIndexSyncStatus = IndexSyncStatus.SYNCED,
                hasUncoveredPendingOperations = false,
            ),
        )
    }

    @Test
    fun fileStateMachineAllowsLifecycleAndRejectsBackwardsTransition() {
        assertTrue(FileStateMachine.canTransition(FileStatus.PENDING, FileStatus.ENCRYPTING))
        assertTrue(FileStateMachine.canTransition(FileStatus.ENCRYPTING, FileStatus.UPLOADING))
        assertFalse(FileStateMachine.canTransition(FileStatus.UPLOADING, FileStatus.AVAILABLE))
        assertTrue(FileStateMachine.canTransition(FileStatus.AVAILABLE, FileStatus.DOWNLOADING))
        assertTrue(FileStateMachine.canTransition(FileStatus.DOWNLOADING, FileStatus.AVAILABLE))
        assertTrue(FileStateMachine.canTransition(FileStatus.DELETING, FileStatus.PARTIALLY_DELETED))
        assertFalse(FileStateMachine.canTransition(FileStatus.AVAILABLE, FileStatus.DELETING))
        assertTrue(FileStateMachine.canBeginCloudDeletion(FileStatus.AVAILABLE))
        assertTrue(FileStateMachine.canBeginCloudDeletion(FileStatus.DOWNLOADING))
        assertFalse(FileStateMachine.canTransition(FileStatus.AVAILABLE, FileStatus.PENDING))
        assertFalse(FileStateMachine.canTransition(FileStatus.CORRUPTED, FileStatus.AVAILABLE))
        assertFalse(
            FileStateMachine.canTransition(FileStatus.FAILED, FileStatus.UPLOADING, true),
        )
        assertFalse(
            FileStateMachine.canTransition(FileStatus.FAILED, FileStatus.DOWNLOADING, false),
        )
        assertTrue(
            FileStateMachine.canTransition(FileStatus.FAILED, FileStatus.UPLOADING, false),
        )
        assertTrue(
            FileStateMachine.canTransition(FileStatus.FAILED, FileStatus.DOWNLOADING, true),
        )
    }
}
