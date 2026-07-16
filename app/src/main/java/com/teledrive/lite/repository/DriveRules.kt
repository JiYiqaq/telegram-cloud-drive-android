package com.teledrive.lite.repository

import com.teledrive.lite.model.FileStatus
import com.teledrive.lite.model.IndexSyncStatus

object DriveNameEquivalence {
    /** Mirrors SQLite's built-in NOCASE collation, which folds ASCII A-Z only. */
    fun sqliteNoCaseKey(name: String): String = buildString(name.length) {
        name.forEach { character ->
            append(
                if (character in 'A'..'Z') {
                    (character.code + ASCII_CASE_OFFSET).toChar()
                } else {
                    character
                },
            )
        }
    }

    private const val ASCII_CASE_OFFSET = 'a'.code - 'A'.code
}

data class CloudIndexIdentity(
    val revision: Long,
    val currentMessageId: Long?,
    val currentFileId: String?,
    val previousMessageId: Long?,
)

enum class CloudCacheReplacementDecision {
    REPLACE,
    KEEP_CURRENT,
    REJECT_STALE,
    REJECT_FORK,
}

object CloudCacheReplacementPolicy {
    fun mustPreserveLocalState(
        hasLocalOnlyFiles: Boolean,
        currentIndexSyncStatus: IndexSyncStatus?,
        hasUncoveredPendingOperations: Boolean,
    ): Boolean = hasLocalOnlyFiles ||
        hasUncoveredPendingOperations ||
        currentIndexSyncStatus in UNSAFE_REPLACEMENT_STATES

    fun decide(
        current: CloudIndexIdentity?,
        incoming: CloudIndexIdentity,
    ): CloudCacheReplacementDecision {
        current ?: return CloudCacheReplacementDecision.REPLACE
        if (incoming.revision < current.revision) {
            return CloudCacheReplacementDecision.REJECT_STALE
        }
        if (incoming.revision == current.revision) {
            return if (
                incoming.currentMessageId == current.currentMessageId &&
                incoming.currentFileId == current.currentFileId
            ) {
                CloudCacheReplacementDecision.KEEP_CURRENT
            } else {
                CloudCacheReplacementDecision.REJECT_FORK
            }
        }
        val isImmediateSuccessor = current.revision != Long.MAX_VALUE &&
            incoming.revision == current.revision + 1
        if (isImmediateSuccessor && incoming.previousMessageId != current.currentMessageId) {
            return CloudCacheReplacementDecision.REJECT_FORK
        }
        return CloudCacheReplacementDecision.REPLACE
    }

    private val UNSAFE_REPLACEMENT_STATES = setOf(
        IndexSyncStatus.DIRTY,
        IndexSyncStatus.SYNCING,
        IndexSyncStatus.FAILED,
    )
}

object DriveNameValidator {
    const val MAX_NAME_LENGTH: Int = 255

    fun isValid(name: String): Boolean =
        name.isNotBlank() &&
            name == name.trim() &&
            name != "." &&
            name != ".." &&
            name.length <= MAX_NAME_LENGTH &&
            '/' !in name &&
            '\\' !in name &&
            name.none(Char::isISOControl)

    fun requireValid(name: String): String = name.also {
        require(isValid(it)) { "Invalid drive entry name" }
    }
}

object FileStateMachine {
    private val transitions = mapOf(
        FileStatus.PENDING to setOf(FileStatus.ENCRYPTING, FileStatus.FAILED),
        FileStatus.ENCRYPTING to setOf(FileStatus.UPLOADING, FileStatus.FAILED),
        // AVAILABLE is only entered by applying a newly pinned cloud-index snapshot.
        FileStatus.UPLOADING to setOf(FileStatus.FAILED),
        FileStatus.AVAILABLE to setOf(
            FileStatus.DOWNLOADING,
            FileStatus.CORRUPTED,
        ),
        FileStatus.DOWNLOADING to setOf(
            FileStatus.AVAILABLE,
            FileStatus.FAILED,
            FileStatus.CORRUPTED,
        ),
        FileStatus.FAILED to setOf(
            FileStatus.PENDING,
            FileStatus.ENCRYPTING,
            FileStatus.UPLOADING,
            FileStatus.DOWNLOADING,
        ),
        FileStatus.DELETING to setOf(FileStatus.PARTIALLY_DELETED),
        FileStatus.PARTIALLY_DELETED to setOf(FileStatus.DELETING),
        FileStatus.CORRUPTED to setOf(FileStatus.DOWNLOADING),
    )

    fun canTransition(from: FileStatus, to: FileStatus): Boolean =
        from == to || to in transitions.getValue(from)

    fun canTransition(
        from: FileStatus,
        to: FileStatus,
        isCloudIndexed: Boolean,
    ): Boolean {
        if (!canTransition(from, to)) return false
        return when (to) {
            FileStatus.PENDING,
            FileStatus.ENCRYPTING,
            FileStatus.UPLOADING -> !isCloudIndexed

            FileStatus.AVAILABLE,
            FileStatus.DOWNLOADING,
            FileStatus.CORRUPTED,
            FileStatus.DELETING,
            FileStatus.PARTIALLY_DELETED -> isCloudIndexed

            FileStatus.FAILED -> true
        }
    }

    fun requireTransition(from: FileStatus, to: FileStatus) {
        require(canTransition(from, to)) { "Invalid file state transition: $from -> $to" }
    }

    fun canBeginCloudDeletion(from: FileStatus): Boolean =
        from in CLOUD_MEMBER_DELETION_STATES

    private val CLOUD_MEMBER_DELETION_STATES = setOf(
        FileStatus.AVAILABLE,
        FileStatus.DOWNLOADING,
        FileStatus.FAILED,
        FileStatus.CORRUPTED,
    )
}
