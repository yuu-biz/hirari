package io.github.yuubiz.hirari.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.ui.chosen
import io.github.yuubiz.hirari.core.ui.decorative

/** The kind mark of a cover (docs/spec.md sections 4.3 and 4.4): a label such as `ZIP`, or the folder icon. */
sealed interface CoverBadge {
    data class Label(val text: String) : CoverBadge

    data object Folder : CoverBadge
}

/**
 * Where a cover goes (docs/spec.md section 4.4): a plain square with the [badge] in its corner, or in its middle when
 * the cover cannot be shown. In the corner the badge is at most a third of the cover wide, its label 11 sp at most.
 * [content] draws the cover between the two.
 */
@Composable
fun CoverSlot(
    badge: CoverBadge?,
    modifier: Modifier = Modifier,
    faded: Boolean = false,
    centered: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.background(if (faded) scheme.surfaceVariant.copy(alpha = 0.4f) else scheme.surfaceVariant, RoundedCornerShape(4.dp)),
    ) {
        content()
        if (badge == null) return@Box
        val chip = Modifier.background(scheme.inverseSurface, RoundedCornerShape(2.dp))
        if (centered) {
            Box(Modifier.align(Alignment.Center).decorative()) {
                when (badge) {
                    is CoverBadge.Label -> Text(badge.text, chip.padding(horizontal = 3.dp), color = scheme.inverseOnSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    CoverBadge.Folder -> Icon(ListIcons.Folder, null, chip.padding(4.dp).size(24.dp), tint = scheme.inverseOnSurface)
                }
            }
        } else {
            Box(Modifier.align(Alignment.BottomEnd).fillMaxWidth(1f / 3).padding(2.dp).decorative(), contentAlignment = Alignment.BottomEnd) {
                when (badge) {
                    is CoverBadge.Label -> BasicText(
                        badge.text,
                        chip.padding(horizontal = 2.dp),
                        style = TextStyle(color = scheme.inverseOnSurface, fontSize = BADGE_SIZE, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = BADGE_MIN_SIZE, maxFontSize = BADGE_SIZE, stepSize = 0.5.sp),
                    )
                    CoverBadge.Folder -> Icon(ListIcons.Folder, null, chip.padding(horizontal = 2.dp).size(BADGE_ICON_WIDTH, BADGE_ICON_HEIGHT), tint = scheme.inverseOnSurface)
                }
            }
        }
    }
}

private val BADGE_SIZE = 11.sp
private val BADGE_MIN_SIZE = 6.sp
private val BADGE_ICON_WIDTH = 18.dp
private val BADGE_ICON_HEIGHT = 14.dp

/** The kind badge of docs/spec.md section 4.3, 形式の表示. A folder book and a single image have none. */
fun Book.kindBadge(): CoverBadge? = kindLabel()?.let(CoverBadge::Label)

private fun Book.kindLabel(): String? = when (val format = format) {
    is BookFormat.Text -> "TXT"
    is BookFormat.Images -> when (format.container) {
        Container.Zip -> "ZIP"
        Container.Rar -> "RAR"
        Container.SevenZip -> "7Z"
        Container.Tar -> "TAR"
        Container.Pdf -> "PDF"
        Container.Folder, Container.Image -> null
    }
}

/** How the shelf names a place: SMB servers as `smb://user@host/share/` without the password (section 4.5). */
fun LibraryLocation.address(): String? = when (this) {
    is LibraryLocation.SafTree -> null
    is LibraryLocation.SmbShare -> "smb://${user?.let { "$it@" }.orEmpty()}$host${if (port == 445) "" else ":$port"}/$share/"
}

@StringRes
fun LibrarySort.label(): Int = when (this) {
    LibrarySort.Name -> R.string.sort_name
    LibrarySort.Newest -> R.string.sort_newest
    LibrarySort.Oldest -> R.string.sort_oldest
}

/** The view the file list's view button turns to next (docs/spec.md section 4.3, 表示形式). */
fun LibraryView.next(): LibraryView = LibraryView.entries[(ordinal + 1) % LibraryView.entries.size]

@StringRes
fun LibraryView.label(): Int = when (this) {
    LibraryView.Grid -> R.string.view_grid
    LibraryView.ThumbnailList -> R.string.view_thumbnail_list
    LibraryView.List -> R.string.view_list
}

@StringRes
fun HistorySort.label(): Int = when (this) {
    HistorySort.PathAscending -> R.string.sort_path_ascending
    HistorySort.PathDescending -> R.string.sort_path_descending
    HistorySort.Oldest -> R.string.sort_oldest
    HistorySort.Newest -> R.string.sort_newest
}

@StringRes
fun BookmarkSort.label(): Int = when (this) {
    BookmarkSort.PathAscending -> R.string.sort_path_ascending
    BookmarkSort.PathDescending -> R.string.sort_path_descending
    BookmarkSort.Oldest -> R.string.sort_oldest
    BookmarkSort.Newest -> R.string.sort_newest
    BookmarkSort.NameAscending -> R.string.sort_name_ascending
    BookmarkSort.NameDescending -> R.string.sort_name_descending
}

/**
 * A button that shows [selected] and drops down every choice. A screen reader says [name] and, as its state, the
 * current choice.
 */
@Composable
fun <T> ChoiceMenu(name: String, selected: T, choices: List<T>, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = label(selected)
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics {
            contentDescription = name
            stateDescription = current
        }) { Text(current, Modifier.decorative()) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(label(choice), fontWeight = if (choice == selected) FontWeight.Bold else null) },
                    modifier = Modifier.chosen(choice == selected),
                    onClick = {
                        open = false
                        onSelect(choice)
                    },
                )
            }
        }
    }
}

/** A dialog that edits one name. Confirming a blank name is not offered. */
@Composable
fun RenameDialog(@StringRes title: Int, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(stringResource(title)) }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@StringRes
fun Notice.message(): Int = when (this) {
    Notice.GrantRefused -> R.string.notice_grant_refused
    Notice.BookMissing -> R.string.notice_book_missing
    Notice.PlaceUnavailable -> R.string.notice_place_unavailable
    Notice.NotInPlace -> R.string.notice_not_in_place
    Notice.TextNotReady -> R.string.notice_text_not_ready
    Notice.ShortcutMissing -> R.string.notice_shortcut_missing
    Notice.ShortcutUnsupported -> R.string.notice_shortcut_unsupported
    Notice.IncomingUnreadable -> R.string.notice_incoming_unreadable
    Notice.SaveStarted -> R.string.notice_save_started
    Notice.StartUnplanned -> R.string.notice_start_unplanned
}
