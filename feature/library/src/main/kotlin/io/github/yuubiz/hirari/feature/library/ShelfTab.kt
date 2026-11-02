package io.github.yuubiz.hirari.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.UriString

@Composable
internal fun ShelfTab(shelf: Shelf, home: HomeViewModel, actions: HomeActions) {
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { home.addTree(UriString(it.toString())) }
    }
    var renaming by remember { mutableStateOf<LibraryLocation?>(null) }
    var removing by remember { mutableStateOf<LibraryLocation?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (shelf.continueReading.isNotEmpty()) {
            item(key = "continue-header") { SectionHeader(R.string.shelf_continue) }
            item(key = "continue") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shelf.continueReading, key = { it.book.book.id.value }) { item ->
                        ContinueCard(item) { home.request(item.book.book)?.let(actions::open) }
                    }
                }
            }
        }
        if (shelf.pinned.isNotEmpty()) {
            item(key = "pinned-header") { SectionHeader(R.string.shelf_pinned) }
            items(shelf.pinned, key = { "pin-${it.pin.id}" }) { item ->
                val name = item.pin.path.substringAfterLast('/').ifEmpty { item.place?.displayName.orEmpty() }
                ListItem(
                    headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOfNotNull(item.place?.displayName, item.pin.path.ifEmpty { null }).joinToString(" / ")) },
                    modifier = Modifier.clickable { home.folderOf(item)?.let(actions.openFolder) },
                )
            }
        }
        item(key = "places-header") { SectionHeader(R.string.shelf_places) }
        if (shelf.places.isEmpty()) item(key = "places-empty") { EmptyPlaces() }
        items(shelf.places, key = { "place-${it.id.value}" }) { place ->
            PlaceRow(
                place,
                onOpen = { actions.openFolder(FolderKey(place.id, "")) },
                onRepick = { pickTree.launch(null) },
                onEdit = { actions.editServer(place.id) },
                onRename = { renaming = place },
                onRemove = { removing = place },
            )
        }
        item(key = "add-place") { AddPlace(onFolder = { pickTree.launch(null) }, onServer = { actions.editServer(null) }) }
    }
    renaming?.let { place ->
        RenameDialog(R.string.place_rename, place.displayName, onDismiss = { renaming = null }) {
            renaming = null
            home.renamePlace(place.id, it)
        }
    }
    removing?.let { place ->
        AlertDialog(
            onDismissRequest = { removing = null },
            text = { Text(stringResource(R.string.place_remove_confirm, place.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    home.removePlace(place.id)
                }) { Text(stringResource(R.string.place_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionHeader(text: Int) {
    Text(
        stringResource(text),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EmptyPlaces() {
    Text(
        stringResource(R.string.shelf_no_places),
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ContinueCard(item: ContinueItem, onOpen: () -> Unit) {
    Column(Modifier.width(96.dp).clickable(onClick = onOpen)) {
        Box(Modifier.size(96.dp, 136.dp)) {
            CoverSlot(item.book.book.kindLabel(), Modifier.fillMaxSize())
            LinearProgressIndicator(
                progress = { item.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
            )
        }
        Text("${item.percent}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(item.book.book.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 場所を追加 offers a folder of the device or an SMB server (docs/spec.md section 4.5). */
@Composable
private fun AddPlace(onFolder: () -> Unit, onServer: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(R.string.place_add), color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.place_add_folder)) }, onClick = {
                menu = false
                onFolder()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.place_add_server)) }, onClick = {
                menu = false
                onServer()
            })
        }
    }
}

/**
 * A place on the shelf; a long press offers renaming, editing a server, and removing it (docs/spec.md section
 * 4.2). A SAF place that lost its grant does not open: tapping it picks the folder again (section 7).
 */
@Composable
private fun PlaceRow(
    place: LibraryLocation,
    onOpen: () -> Unit,
    onRepick: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val detail = when (place) {
        is LibraryLocation.SafTree -> if (place.permissionHeld) null else stringResource(R.string.place_permission_lost)
        is LibraryLocation.SmbShare -> place.address()
    }
    val onTap = when (place) {
        is LibraryLocation.SafTree -> if (place.permissionHeld) onOpen else onRepick
        is LibraryLocation.SmbShare -> onOpen
    }
    Box {
        ListItem(
            headlineContent = { Text(place.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = detail?.let { { Text(it) } },
            modifier = Modifier.combinedClickable(enabled = true, onClick = onTap, onLongClick = { menu = true }),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (place is LibraryLocation.SafTree && !place.permissionHeld) {
                DropdownMenuItem(text = { Text(stringResource(R.string.place_repick)) }, onClick = {
                    menu = false
                    onRepick()
                })
            }
            if (place is LibraryLocation.SmbShare) {
                DropdownMenuItem(text = { Text(stringResource(R.string.place_edit)) }, onClick = {
                    menu = false
                    onEdit()
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.place_rename)) }, onClick = {
                menu = false
                onRename()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.place_remove)) }, onClick = {
                menu = false
                onRemove()
            })
        }
    }
}
