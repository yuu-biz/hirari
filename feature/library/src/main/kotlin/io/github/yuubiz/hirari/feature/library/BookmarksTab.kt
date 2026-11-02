package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.data.BookmarkGroup
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.Bookmark

@Composable
internal fun BookmarksTabContent(tab: BookmarksTab, home: HomeViewModel, actions: HomeActions) {
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 8.dp)) {
            ChoiceMenu(tab.sort, BookmarkSort.entries, { stringResource(it.label()) }, home::setBookmarkSort)
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
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
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
    Box {
        ListItem(
            headlineContent = { Text(mark.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.combinedClickable(
                onClick = { home.request(group, mark.position)?.let(actions::open) },
                onLongClick = { menu = true },
            ),
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
