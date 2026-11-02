package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.LibraryLocation

@Composable
internal fun ShelfTab(shelf: Shelf, home: HomeViewModel, actions: HomeActions) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (shelf.continueReading.isNotEmpty()) {
            item(key = "continue-header") { SectionHeader(R.string.shelf_continue) }
            item(key = "continue") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shelf.continueReading, key = { it.book.book.id.value }) { item ->
                        ContinueCard(item) { home.request(item.book.book)?.let(actions.openBook) }
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
            PlaceRow(place, onOpen = { actions.openFolder(FolderKey(place.id, "")) })
        }
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

/** A place on the shelf. A SAF place that lost its grant cannot open until its folder is picked again (section 7). */
@Composable
internal fun PlaceRow(place: LibraryLocation, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val usable = place is LibraryLocation.SafTree && place.permissionHeld
    val detail = when (place) {
        is LibraryLocation.SafTree -> if (place.permissionHeld) null else stringResource(R.string.place_permission_lost)
        is LibraryLocation.SmbShare -> place.address()
    }
    ListItem(
        headlineContent = { Text(place.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = detail?.let { { Text(it) } },
        modifier = modifier.then(if (usable) Modifier.clickable(onClick = onOpen) else Modifier),
    )
}
