package io.github.yuubiz.hirari.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

enum class HomeTab(@StringRes val label: Int, @StringRes val empty: Int) {
    Shelf(R.string.home_tab_shelf, R.string.home_empty_shelf),
    History(R.string.home_tab_history, R.string.home_empty_history),
    Bookmarks(R.string.home_tab_bookmarks, R.string.home_empty_bookmarks),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(actions: @Composable () -> Unit = {}) {
    var selected by rememberSaveable { mutableStateOf(HomeTab.Shelf) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = { actions() },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selected.ordinal) {
                HomeTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        text = { Text(stringResource(tab.label)) },
                    )
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(selected.empty))
            }
        }
    }
}
