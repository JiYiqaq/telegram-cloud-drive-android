package com.teledrive.lite.repository

import com.teledrive.lite.database.IndexStateEntity
import com.teledrive.lite.model.IndexSyncStatus
import com.teledrive.lite.model.PendingOperationType

/**
 * A dirty index can still provide a stable base for another deletion when deletion operations
 * are the only reason it is dirty. The deletion workers serialize their remote commits.
 */
object DeletionIndexAdmissionPolicy {
    fun canStart(
        syncStatus: IndexSyncStatus,
        pendingOperationTypes: Collection<PendingOperationType>,
    ): Boolean = when (syncStatus) {
        IndexSyncStatus.SYNCED -> true
        IndexSyncStatus.DIRTY ->
            pendingOperationTypes.isNotEmpty() &&
                pendingOperationTypes.all { it == PendingOperationType.DELETE }

        else -> false
    }
}

/**
 * Accepts both the legacy pre-commit transition and the normal state where IndexAtomicUpdater
 * has already committed the exact verified pointer before local deletion metadata is finalized.
 */
object DeletionIndexConfirmationPolicy {
    fun isConfirmed(
        operationBaseRevision: Long,
        current: IndexStateEntity,
        confirmed: IndexStateEntity,
    ): Boolean {
        if (operationBaseRevision > current.revision) return false

        val alreadyCommitted =
            operationBaseRevision < current.revision &&
                confirmed.revision == current.revision &&
                confirmed.currentIndexMessageId == current.currentIndexMessageId &&
                confirmed.previousIndexMessageId == current.previousIndexMessageId &&
                confirmed.currentIndexFileId == current.currentIndexFileId
        if (alreadyCommitted) return true

        return confirmed.revision - current.revision == 1L &&
            confirmed.previousIndexMessageId == current.currentIndexMessageId &&
            confirmed.currentIndexMessageId != current.currentIndexMessageId &&
            confirmed.currentIndexFileId != current.currentIndexFileId
    }
}
