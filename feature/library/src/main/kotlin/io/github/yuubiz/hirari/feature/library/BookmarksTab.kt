package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.data.BookmarkGroup
import io.github.yuubiz.hirari.core.data.BookmarkScope
import io.github.yuubiz.hirari.core.model.Bookmark
import io.github.yuubiz.hirari.core.model.BookmarkSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BookmarksTabContent(tab: BookmarksTab, home: HomeViewModel, actions: HomeActions) {
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp)) {
            ChoiceMenu(stringResource(R.string.sort_label), tab.sort, BookmarkSort.entries, { stringResource(it.label()) }, home::setBookmarkSort)
            BookmarkDeleteMenu(tab, home)
        }
        if (tab.groups.isEmpty()) {
            EmptyNote(R.string.home_empty_bookmarks)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                tab.groups.forEach { group ->
                    item(key = "book-${group.book.value}") { GroupHeader(group) }
                    items(group.bookmarks, key = { "mark-${it.id}" }) { mark ->
                        BookmarkRow(group, mark, home, actions, onRename = { renaming = mark })
                    }
                }
            }
        }
    }
    renaming?.let { mark ->
        RenameDialog(R.string.bookmark_rename, mark.label, onDismiss = { renaming = null }) {
            renaming = null
            home.renameBookmark(mark.id, it)
        }
    }
}

@Composable
private fun GroupHeader(group: BookmarkGroup) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics(mergeDescendants = true) { heading() }) {
        Text(
            group.info?.title ?: stringResource(R.string.book_not_indexed),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        group.location?.let { Text(it.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun BookmarkRow(group: BookmarkGroup, mark: Bookmark, home: HomeViewModel, actions: HomeActions, onRename: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val rename = stringResource(R.string.bookmark_rename)
    val delete = stringResource(R.string.bookmark_delete)
    Box {
        ListItem(
            headlineContent = { Text(mark.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier
                .combinedClickable(
                    onLongClickLabel = stringResource(R.string.row_menu),
                    onClick = { home.request(group, mark.position)?.let(actions::open) },
                    onLongClick = { menu = true },
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(rename) {
                            onRename()
                            true
                        },
                        CustomAccessibilityAction(delete) {
                            home.deleteBookmark(mark.id)
                            true
                        },
                    )
                },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.bookmark_rename)) }, onClick = {
                menu = false
                onRename()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.bookmark_delete)) }, onClick = {
                menu = false
                home.deleteBookmark(mark.id)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.open_folder)) }, onClick = {
                menu = false
                home.folderOf(group.info)?.let(actions.openFolder)
            })
        }
    }
}

@Composable
private fun BookmarkDeleteMenu(tab: BookmarksTab, home: HomeViewModel) {
    var menu by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<BookmarkDeletion?>(null) }
    val scope = rememberCoroutineScope()
    fun choose(value: BookmarkScope?) {
        choosing = false
        busy = true
        scope.launch {
            try {
                plan = home.bookmarkDeletion(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    Box {
        IconButton(onClick = { menu = true }, enabled = !busy) {
            Icon(ListIcons.More, contentDescription = stringResource(R.string.row_menu))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_bulk_delete)) }, onClick = { menu = false; choosing = true })
        }
    }
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.bookmarks_bulk_delete)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { choose(BookmarkScope.All) }) { Text(stringResource(R.string.bookmarks_scope_all)) }
                TextButton(onClick = { choose(null) }) { Text(stringResource(R.string.bookmarks_scope_missing)) }
                TextButton(onClick = { choose(BookmarkScope.OnDevice) }) { Text(stringResource(R.string.bookmarks_scope_device)) }
                val places = tab.groups.mapNotNull { it.location }.distinctBy { it.id }
                places.forEach { place ->
                    TextButton(onClick = { choose(BookmarkScope.InLocation(place.id)) }) {
                        Text(stringResource(R.string.bookmarks_scope_place, place.displayName))
                    }
                    val folders = tab.groups.mapNotNull { (it.info?.source as? io.github.yuubiz.hirari.core.model.BookSource.InLocation)?.takeIf { it.location == place.id } }
                        .flatMap { source ->
                            val parts = source.path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
                            listOf("") + parts.indices.map { parts.take(it + 1).joinToString("/") }
                        }.distinct().sorted()
                    folders.forEach { folder ->
                        TextButton(onClick = { choose(BookmarkScope.UnderFolder(place.id, folder)) }) {
                            Text(stringResource(R.string.bookmarks_scope_folder, place.displayName, folder.ifEmpty { "/" }))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } },
    )
    plan?.let { deletion ->
        AlertDialog(
            onDismissRequest = { plan = null },
            text = { Text(stringResource(R.string.bookmarks_delete_confirm, deletion.count)) },
            confirmButton = { TextButton(enabled = deletion.count > 0, onClick = { plan = null; home.deleteBookmarks(deletion) }) { Text(stringResource(R.string.bookmark_delete)) } },
            dismissButton = { TextButton(onClick = { plan = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (failed) AlertDialog(
        onDismissRequest = { failed = false },
        text = { Text(stringResource(R.string.bookmarks_check_failed)) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
