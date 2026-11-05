package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.data.FavoriteKind
import io.github.yuubiz.hirari.core.model.FileFormats
import java.util.Locale

@Composable
internal fun FavoritesTabContent(tab: FavoritesTab, home: HomeViewModel, actions: HomeActions) {
    if (tab.items.isEmpty()) {
        EmptyNote(R.string.favorites_empty)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(tab.items, key = { "${it.favorite.location.value}:${it.favorite.path}" }) { item ->
            val favorite = item.favorite
            val badge = when {
                favorite.kind == FavoriteKind.Folder -> CoverBadge.Folder
                favorite.path.substringAfterLast('.', "").lowercase(Locale.ROOT) in FileFormats.texts -> CoverBadge.Label("TXT")
                else -> null
            }
            val cover by remember(home, item) { home.favoriteCover(item) }.collectAsState(initial = null)
            var menu by remember { mutableStateOf(false) }
            val remove = stringResource(R.string.favorite_remove)
            Box {
                ListItem(
                    leadingContent = {
                        CoverBox(cover?.let { CoverRef(it, tab.covers.sizePx) }, tab.covers.covers, badge, Modifier.size(40.dp, 56.dp))
                    },
                    headlineContent = { Text(favorite.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(item.place?.displayName ?: stringResource(R.string.favorite_place_missing), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.combinedClickable(
                        role = Role.Button,
                        onLongClickLabel = stringResource(R.string.row_menu),
                        onClick = { home.request(item)?.let(actions::open) },
                        onLongClick = { menu = true },
                    ).semantics {
                        customActions = listOf(CustomAccessibilityAction(remove) { home.removeFavorite(item); true })
                    },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(remove) }, onClick = {
                        menu = false
                        home.removeFavorite(item)
                    })
                }
            }
        }
    }
}
