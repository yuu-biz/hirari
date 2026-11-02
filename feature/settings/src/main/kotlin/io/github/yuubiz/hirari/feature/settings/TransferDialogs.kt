package io.github.yuubiz.hirari.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.data.ImportChanges

@Composable
internal fun ExportDialog(includeReading: Boolean, onIncludeReading: (Boolean) -> Unit, onChoosePlace: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.export_body))
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp).toggleable(includeReading, role = Role.Checkbox, onValueChange = onIncludeReading),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeReading, onCheckedChange = null, modifier = Modifier.padding(end = 16.dp))
                    Text(stringResource(R.string.export_include_reading))
                }
            }
        },
        confirmButton = { TextButton(onClick = onChoosePlace) { Text(stringResource(R.string.export_choose_place)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
internal fun ConfirmImportDialog(changes: ImportChanges, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (changes.total == 0) {
                    Text(stringResource(R.string.import_nothing))
                } else {
                    Text(stringResource(R.string.import_intro))
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.import_total, changes.total), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.import_settings, changes.settings))
                        Text(stringResource(R.string.import_overrides, changes.bookOverrides))
                        Text(stringResource(R.string.import_servers, changes.servers))
                        Text(stringResource(R.string.import_progress, changes.progress))
                        Text(stringResource(R.string.import_bookmarks, changes.bookmarks))
                        Text(stringResource(R.string.import_history, changes.history))
                    }
                    if (changes.progress + changes.bookmarks + changes.history == 0) {
                        Text(stringResource(R.string.import_no_reading), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (changes.servers > 0) {
                        Text(stringResource(R.string.import_servers_note), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (changes.total == 0) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_close)) }
            } else {
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.import_start)) }
            }
        },
        dismissButton = if (changes.total == 0) null else ({ TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } }),
    )
}

@Composable
internal fun WorkingDialog() {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 16.dp))
                Text(stringResource(R.string.settings_working))
            }
        },
    )
}

@Composable
internal fun ResultDialog(result: ActionResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(result.messageRes())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } },
    )
}

private fun ActionResult.messageRes(): Int = when (this) {
    is ActionResult.Exported -> if (includedReading) R.string.export_done_with_reading else R.string.export_done
    is ActionResult.Imported -> R.string.import_done
    ActionResult.ThumbnailsCleared -> R.string.clear_thumbnails_done
    is ActionResult.Failed -> when (problem) {
        Problem.CannotWrite -> R.string.problem_cannot_write
        Problem.CannotRead -> R.string.problem_cannot_read
        Problem.TooLarge -> R.string.problem_too_large
        Problem.NotASettingsFile -> R.string.problem_not_a_settings_file
        Problem.ApplyFailed -> R.string.problem_apply_failed
        Problem.CannotClear -> R.string.problem_cannot_clear
        Problem.CannotSave -> R.string.problem_cannot_save
    }
}
