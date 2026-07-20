package com.teledrive.lite.repository

import com.teledrive.lite.database.IndexStateEntity
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.PendingOperationType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionIndexPoliciesTest {
    @Test
    fun dirtyIndexAcceptsAnotherDeletionOnlyWhenAllPendingWorkIsDeletion() {
        assertTrue(
            DeletionIndexAdmissionPolicy.canStart(
                syncStatus = IndexSyncStatus.SYNCED,
                pendingOperationTypes = emptyList(),
            ),
        )
        assertTrue(
            DeletionIndexAdmissionPolicy.canStart(
                syncStatus = IndexSyncStatus.DIRTY,
                pendingOperationTypes = listOf(
                    PendingOperationType.DELETE,
                    PendingOperationType.DELETE,
                ),
            ),
        )
        assertFalse(
            DeletionIndexAdmissionPolicy.canStart(
                syncStatus = IndexSyncStatus.DIRTY,
                pendingOperationTypes = listOf(
                    PendingOperationType.DELETE,
                    PendingOperationType.MOVE,
                ),
            ),
        )
        assertFalse(
            DeletionIndexAdmissionPolicy.canStart(
                syncStatus = IndexSyncStatus.DIRTY,
                pendingOperationTypes = emptyList(),
            ),
        )
        assertFalse(
            DeletionIndexAdmissionPolicy.canStart(
                syncStatus = IndexSyncStatus.SYNCING,
                pendingOperationTypes = listOf(PendingOperationType.DELETE),
            ),
        )
    }

    @Test
    fun finalizationAcceptsTheIndexPointerAlreadyCommittedByAtomicUpdater() {
        val committed = indexState(
            revision = 5,
            messageId = 91,
            previousMessageId = 90,
            fileId = "index-file-5",
        )

        assertTrue(
            DeletionIndexConfirmationPolicy.isConfirmed(
                operationBaseRevision = 4,
                current = committed.copy(syncStatus = IndexSyncStatus.DIRTY),
                confirmed = committed.copy(syncStatus = IndexSyncStatus.SYNCED),
            ),
        )
        assertFalse(
            DeletionIndexConfirmationPolicy.isConfirmed(
                operationBaseRevision = 4,
                current = committed,
                confirmed = committed.copy(currentIndexFileId = "forked-index-file"),
            ),
        )
        assertFalse(
            DeletionIndexConfirmationPolicy.isConfirmed(
                operationBaseRevision = committed.revision,
                current = committed,
                confirmed = committed,
            ),
        )
    }

    private fun indexState(
        revision: Long,
        messageId: Long,
        previousMessageId: Long,
        fileId: String,
    ) = IndexStateEntity(
        id = IndexStateEntity.SINGLETON_ID,
        formatVersion = 1,
        revision = revision,
        rootFolderId = "root",
        currentIndexMessageId = messageId,
        previousIndexMessageId = previousMessageId,
        currentIndexFileId = fileId,
        lastSyncedAtEpochMillis = 1_700_000_000_000L,
        syncStatus = IndexSyncStatus.SYNCED,
    )
}
