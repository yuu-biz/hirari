package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import io.github.yuubiz.hirari.core.source.DELETE_COUNT_LIMIT
import io.github.yuubiz.hirari.core.source.EditableName
import io.github.yuubiz.hirari.core.source.validFileStem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FileEditDialog(
    row: FileRow,
    rename: Boolean,
    count: suspend (FileRow, (Int) -> Unit) -> Int,
    onConfirm: suspend (String) -> FileEditResult,
    onDismiss: () -> Unit,
) {
    val name = remember(row) { EditableName.of(row.item.entry.name, row.item is FileItem.Folder) }
    var stem by remember(row) { mutableStateOf(name.stem) }
    var total by remember(row) { mutableIntStateOf(0) }
    var counting by remember(row) { mutableStateOf(!rename && row.item is FileItem.Folder) }
    var running by remember(row) { mutableStateOf(false) }
    var result by remember(row) { mutableStateOf<FileEditResult?>(null) }
    var countFailed by remember(row) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(row, rename) {
        if (counting) {
            try {
                total = count(row) { total = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result = FileEditResult.Failed(e.fileEditFailure())
                countFailed = true
            } finally {
                counting = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(if (rename) R.string.sheet_rename else R.string.sheet_delete)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(row.item.entry.name)
                if (rename) {
                    OutlinedTextField(
                        value = stem,
                        onValueChange = { stem = it; result = null },
                        enabled = !running && !result.finishedWithNotice(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.edit_name)) },
                        suffix = { Text(name.extension) },
                        isError = !validFileStem(stem),
                    )
                    if (!validFileStem(stem)) Text(stringResource(R.string.edit_invalid_name))
                } else {
                    if (row.item is FileItem.Folder && !countFailed) {
                        Text(if (counting) stringResource(R.string.edit_counting, total) else if (total >= DELETE_COUNT_LIMIT)
                            stringResource(R.string.edit_count_capped) else stringResource(R.string.edit_count, total))
                    }
                    Text(stringResource(R.string.edit_delete_records))
                }
                if (counting || running) LinearProgressIndicator()
                when (val shown = result) {
                    FileEditResult.RecordsNotMoved -> Text(stringResource(R.string.edit_records_not_moved))
                    is FileEditResult.Renamed -> {
                        shown.actualName?.let { Text(stringResource(R.string.edit_provider_name, it)) }
                        if (shown.recordsNotMoved) Text(stringResource(R.string.edit_records_not_moved))
                        if (shown.skipped > 0) Text(stringResource(R.string.edit_records_skipped, shown.skipped))
                    }
                    FileEditResult.PartialDelete -> Text(stringResource(R.string.edit_partial_delete))
                    is FileEditResult.Failed -> Text(shown.message())
                    else -> Unit
                }
            }
        },
        confirmButton = {
            if (!countFailed && !result.finishedWithNotice()) TextButton(
                enabled = !counting && !running && (!rename || validFileStem(stem)),
                onClick = {
                    running = true
                    scope.launch {
                        try {
                            result = onConfirm(stem)
                            if (result == FileEditResult.Done) onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            result = FileEditResult.Failed(e.fileEditFailure())
                        } finally {
                            running = false
                        }
                    }
                },
            ) { Text(stringResource(if (rename) R.string.sheet_rename else R.string.sheet_delete)) }
        },
        dismissButton = { TextButton(enabled = !running, onClick = onDismiss) {
            Text(stringResource(if (result.finishedWithNotice() || countFailed) R.string.folder_close else R.string.save_cancel))
        } },
    )
}

@Composable
private fun FileEditResult.Failed.message(): String = when (reason) {
    FileEditFailure.InvalidName -> stringResource(R.string.edit_invalid_name)
    FileEditFailure.NoPermission -> stringResource(R.string.edit_no_permission)
    FileEditFailure.NotFound -> stringResource(R.string.edit_not_found)
    FileEditFailure.NameExists -> stringResource(R.string.edit_name_exists, name)
    FileEditFailure.Unreachable -> stringResource(R.string.edit_unreachable)
    FileEditFailure.Other -> stringResource(R.string.edit_failed)
}

private fun FileEditResult?.finishedWithNotice(): Boolean =
    this is FileEditResult.Renamed || this == FileEditResult.RecordsNotMoved || this == FileEditResult.PartialDelete
