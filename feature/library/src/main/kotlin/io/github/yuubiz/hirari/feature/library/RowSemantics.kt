package io.github.yuubiz.hirari.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** What a screen reader says of a read mark (docs/spec.md section 6.7); the glyph and the progress bar are not read. */
@Composable
internal fun ReadMark.spoken(): String = when (this) {
    ReadMark.Unread -> stringResource(R.string.mark_unread)
    is ReadMark.Reading -> stringResource(R.string.mark_reading_percent, percent)
    ReadMark.Read -> stringResource(R.string.mark_read)
}

/**
 * The state a row reads after its name: the kind of a folder or a book, then its read mark. A single image has neither.
 * The kind label and the mark are drawn in the cover, which a screen reader skips.
 */
@Composable
internal fun Modifier.rowState(row: FileRow): Modifier {
    val kind = when (val item = row.item) {
        is FileItem.Folder -> stringResource(R.string.folder_label)
        is FileItem.Archive -> item.kind.label
        is FileItem.Text -> stringResource(R.string.kind_text)
        is FileItem.Image -> null
    }
    val state = spokenList(kind, row.mark?.spoken(), if (row.favorite) stringResource(R.string.favorite_label) else null)
    return if (state == null) this else semantics { stateDescription = state }
}

/** The parts that exist, in a form a screen reader pauses between. */
internal fun spokenList(vararg parts: String?): String? = parts.filterNotNull().joinToString("、").ifEmpty { null }
