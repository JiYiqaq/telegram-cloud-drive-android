package com.teledrive.lite.settings

import com.teledrive.lite.BuildConfig
import com.teledrive.lite.database.TeleDriveDatabase
import com.teledrive.lite.model.IndexSyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class IndexSummary(
    val revision: Long,
    val lastSyncedAtEpochMillis: Long?,
    val status: IndexSyncStatus,
)

class SettingsRepository(
    private val database: TeleDriveDatabase,
) {
    fun observeIndexSummary(): Flow<IndexSummary> = database.indexStateDao()
        .observe(com.teledrive.lite.database.IndexStateEntity.SINGLETON_ID)
        .map { state ->
            IndexSummary(
                revision = state?.revision ?: 0,
                lastSyncedAtEpochMillis = state?.lastSyncedAtEpochMillis,
                status = state?.syncStatus ?: IndexSyncStatus.NOT_INITIALIZED,
            )
        }

    suspend fun diagnostics(): String {
        val tasks = database.transferTaskDao().getAll()
        val taskCounts = tasks.groupingBy { "${it.type}_${it.status}" }.eachCount().toSortedMap()
        val index = database.indexStateDao()
            .get(com.teledrive.lite.database.IndexStateEntity.SINGLETON_ID)
        return buildString {
            appendLine("TeleDrive Lite diagnostics")
            appendLine("app_version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("android_sdk=${android.os.Build.VERSION.SDK_INT}")
            appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("database_version=${TeleDriveDatabase.DATABASE_VERSION}")
            appendLine("index_format_version=${index?.formatVersion ?: 1}")
            appendLine("index_revision=${index?.revision ?: 0}")
            appendLine("index_status=${index?.syncStatus ?: IndexSyncStatus.NOT_INITIALIZED}")
            appendLine("last_synced_at=${index?.lastSyncedAtEpochMillis ?: "never"}")
            taskCounts.forEach { (status, count) -> appendLine("tasks_$status=$count") }
            appendLine("sensitive_values=redacted")
        }
    }
}
