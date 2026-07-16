package com.teledrive.lite.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.teledrive.lite.R
import com.teledrive.lite.model.DirectoryEntry
import com.teledrive.lite.model.EntryKind
import com.teledrive.lite.model.FileStatus

@Composable
internal fun HomeEntryActions(
    entry: DirectoryEntry,
    downloadable: Boolean,
    deletable: Boolean,
    onDownload: (DirectoryEntry) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenFileDetail: (DirectoryEntry) -> Unit,
    onRename: (DirectoryEntry) -> Unit,
    onMove: (DirectoryEntry) -> Unit,
    onDelete: (DirectoryEntry) -> Unit,
) {
    var menuExpanded by remember(entry.id) { mutableStateOf(false) }
    val opens = entry.kind == EntryKind.FOLDER || downloadable
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        if (opens) {
            FilledTonalButton(
                onClick = {
                    if (entry.kind == EntryKind.FOLDER) onOpenFolder(entry.id) else onDownload(entry)
                },
            ) {
                Text(
                    stringResource(
                        if (entry.kind == EntryKind.FOLDER) R.string.open_folder else R.string.download,
                    ),
                )
            }
        }
        Box {
            TextButton(onClick = { menuExpanded = true }) {
                Text(stringResource(R.string.more_actions))
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                if (entry.kind == EntryKind.FILE) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.details)) },
                        onClick = {
                            menuExpanded = false
                            onOpenFileDetail(entry)
                        },
                    )
                }
                if (deletable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        onClick = {
                            menuExpanded = false
                            onRename(entry)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move)) },
                        onClick = {
                            menuExpanded = false
                            onMove(entry)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    if (entry.fileStatus == FileStatus.PARTIALLY_DELETED) {
                                        R.string.retry_safe_delete
                                    } else {
                                        R.string.delete
                                    },
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete(entry)
                        },
                    )
                }
            }
        }
    }
}
