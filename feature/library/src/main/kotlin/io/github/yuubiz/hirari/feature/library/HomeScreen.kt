package io.github.yuubiz.hirari.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest

enum class HomeTab(@StringRes val label: Int) {
    Shelf(R.string.home_tab_shelf),
    History(R.string.home_tab_history),
    Bookmarks(R.string.home_tab_bookmarks),
}

/** What the home screen asks of the app: the readers, a file list, the SMB form, and the top bar's menu. */
class HomeActions(
    val openBook: (OpenBookRequest) -> Unit,
    val openText: (TextAddress) -> Unit,
    val openFolder: (FolderKey) -> Unit,
    /** The SMB form for a new server, or for the server [LocationId] names. */
    val editServer: (LocationId?) -> Unit,
    val menu: @Composable () -> Unit,
)

fun HomeActions.open(target: HomeTarget) = when (target) {
    is HomeTarget.Book -> openBook(target.request)
    is HomeTarget.Text -> openText(target.address)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(home: HomeViewModel, actions: HomeActions) {
    val state by home.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(HomeTab.Shelf) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(resources.getString(notice.message()))
        home.dismissNotice()
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.home_title)) }, actions = { actions.menu() })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selected.ordinal) {
                HomeTab.entries.forEach { tab ->
                    Tab(selected = tab == selected, onClick = { selected = tab }, text = { Text(stringResource(tab.label)) })
                }
            }
            when (selected) {
                HomeTab.Shelf -> Loaded(state.shelf) { ShelfTab(it, home, actions) }
                HomeTab.History -> Loaded(state.history) { HistoryTabContent(it, home, actions) }
                HomeTab.Bookmarks -> Loaded(state.bookmarks) { BookmarksTabContent(it, home, actions) }
            }
        }
    }
}

@Composable
private fun <T> Loaded(value: Loadable<T>, content: @Composable (T) -> Unit) {
    when (value) {
        Loadable.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Loadable.Ready -> content(value.value)
    }
}

@Composable
internal fun EmptyNote(@StringRes text: Int) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
