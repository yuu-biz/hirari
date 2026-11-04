package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.CoverAspect
import io.github.yuubiz.hirari.core.model.LibraryTextSize
import io.github.yuubiz.hirari.core.model.ThumbSize

/** Whether the window is wider than it is tall, which picks the grid columns of that orientation (docs/spec.md section 4.3). */
@Composable
internal fun isLandscapeWindow(): Boolean = LocalConfiguration.current.run { screenWidthDp > screenHeightDp }

/**
 * 表示の調整 (docs/spec.md section 4.3): the settings that shape the list, over the list without darkening it, so each
 * choice shows in the list above at once. [change] saves a change to the settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LookSheet(look: ListLook, change: ((GlobalSettings) -> GlobalSettings) -> Unit, onDismiss: () -> Unit) {
    val landscape = isLandscapeWindow()
    val small = stringResource(R.string.look_small)
    val medium = stringResource(R.string.look_medium)
    val large = stringResource(R.string.look_large)
    ModalBottomSheet(onDismissRequest = onDismiss, scrimColor = Color.Transparent) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.look_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            val auto = stringResource(R.string.look_auto)
            val columns = columnCount()
            val lines = lineCount()
            if (landscape) {
                Choices(stringResource(R.string.look_columns_landscape), listOf(null) + GlobalSettings.GRID_COLUMNS_LANDSCAPE, look.landscapeColumns, { it?.let(columns) ?: auto }) { v ->
                    change { it.copy(gridColumnsLandscape = v) }
                }
            } else {
                Choices(stringResource(R.string.look_columns_portrait), listOf(null) + GlobalSettings.GRID_COLUMNS_PORTRAIT, look.portraitColumns, { it?.let(columns) ?: auto }) { v ->
                    change { it.copy(gridColumnsPortrait = v) }
                }
            }
            Choices(stringResource(R.string.look_name_lines), GlobalSettings.GRID_NAME_LINES.toList(), look.nameLines, lines) { v ->
                change { it.copy(gridNameLines = v) }
            }
            Row(
                Modifier.fillMaxWidth().toggleable(look.noteLine, role = Role.Switch) { v -> change { it.copy(gridNoteLine = v) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.look_note_line), Modifier.weight(1f))
                Switch(checked = look.noteLine, onCheckedChange = null)
            }
            val book = stringResource(R.string.look_aspect_book)
            val square = stringResource(R.string.look_aspect_square)
            Choices(stringResource(R.string.look_aspect), CoverAspect.entries, look.aspect, { if (it == CoverAspect.Book) book else square }) { v ->
                change { it.copy(coverAspect = v) }
            }
            val textNames = mapOf(LibraryTextSize.Small to small, LibraryTextSize.Medium to medium, LibraryTextSize.Large to large)
            Choices(stringResource(R.string.look_text_size), LibraryTextSize.entries, look.textSize, { textNames.getValue(it) }) { v ->
                change { it.copy(libraryTextSize = v) }
            }
            val thumbNames = mapOf(ThumbSize.Small to small, ThumbSize.Medium to medium, ThumbSize.Large to large)
            Choices(stringResource(R.string.look_thumb_size), ThumbSize.entries, look.thumbSize, { thumbNames.getValue(it) }) { v ->
                change { it.copy(thumbSize = v) }
            }
        }
    }
}

/** A setting's name over its values as chips, the chosen one selected; the chips scroll sideways when they do not fit. */
@Composable
private fun <T> Choices(label: String, values: List<T>, chosen: T, name: (T) -> String, onChoose: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in values) {
                FilterChip(selected = value == chosen, onClick = { onChoose(value) }, label = { Text(name(value)) })
            }
        }
    }
}

/** A column count as the chip shows and a screen reader says it, 「5 列」 rather than a bare number. */
@Composable
private fun columnCount(): (Int) -> String {
    val format = stringResource(R.string.look_columns_count)
    return { format.format(it) }
}

/** A line count as 「2 行」. */
@Composable
private fun lineCount(): (Int) -> String {
    val format = stringResource(R.string.look_lines_count)
    return { format.format(it) }
}
