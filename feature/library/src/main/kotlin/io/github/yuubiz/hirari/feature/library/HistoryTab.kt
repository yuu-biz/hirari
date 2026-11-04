package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.data.HistoryItem
import io.github.yuubiz.hirari.core.model.HistorySort

@Composable
internal fun HistoryTabContent(tab: HistoryTab, home: HomeViewModel, actions: HomeActions) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ChoiceMenu(stringResource(R.string.sort_label), tab.sort, HistorySort.entries, { stringResource(it.label()) }, home::setHistorySort)
            TextButton(onClick = { confirmClear = true }, enabled = tab.items.isNotEmpty()) { Text(stringResource(R.string.history_clear)) }
        }
        if (tab.items.isEmpty()) {
            EmptyNote(R.string.home_empty_history)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(tab.items, key = { it.entry.book.value }) { item ->
                    HistoryRow(item, tab.covers, tab.specs[item.entry.book], home, actions)
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.history_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    home.clearHistory()
                }) { Text(stringResource(R.string.history_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Swiping either way removes the entry (docs/spec.md section 4.6). */
@Composable
private fun HistoryRow(item: HistoryItem, covers: CoverLayout, cover: CoverSpec.Book?, home: HomeViewModel, actions: HomeActions) {
    val swipe = rememberSwipeToDismissBoxState()
    var menu by remember { mutableStateOf(false) }
    val remove = stringResource(R.string.history_remove)
    val reading = item.progress?.let { stringResource(R.string.mark_reading_percent, it.percent()) }
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer)) },
        onDismiss = { home.removeFromHistory(item.entry.book) },
    ) {
        Box {
            ListItem(
                leadingContent = { CoverBox(cover?.let { CoverRef(it, covers.sizePx) }, covers.covers, item.book?.kindBadge(), Modifier.size(40.dp, 56.dp)) },
                headlineContent = {
                    Text(item.book?.title ?: stringResource(R.string.book_not_indexed), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    val percent = item.progress?.let { "${it.percent()}%" }
                    Text(listOfNotNull(item.location?.displayName, percent).joinToString(" · "))
                },
                modifier = Modifier
                    .combinedClickable(
                        onLongClickLabel = stringResource(R.string.row_menu),
                        onClick = { home.request(item.book)?.let(actions::open) },
                        onLongClick = { menu = true },
                    )
                    .semantics {
                        reading?.let { stateDescription = it }
                        customActions = listOf(
                            CustomAccessibilityAction(remove) {
                                home.removeFromHistory(item.entry.book)
                                true
                            },
                        )
                    },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.open_folder)) }, onClick = {
                    menu = false
                    home.folderOf(item.book)?.let(actions.openFolder)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.history_remove)) }, onClick = {
                    menu = false
                    home.removeFromHistory(item.entry.book)
                })
            }
        }
    }
}
