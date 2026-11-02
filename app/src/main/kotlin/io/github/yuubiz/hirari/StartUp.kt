package io.github.yuubiz.hirari

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import io.github.yuubiz.hirari.core.data.LastFolder
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.feature.library.Reopen
import io.github.yuubiz.hirari.feature.library.planStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach

/**
 * Starts the app as docs/spec.md section 4.3 (起動時の動作) says. On a [fresh] start it opens the planned folder
 * and book over home, or asks about the book. Then it keeps the last folder: the folder of the file list last on
 * top, cleared when home comes on top and kept under the other screens.
 */
@Composable
internal fun StartUp(nav: NavController, app: HirariApp, fresh: Boolean) {
    var asking by remember { mutableStateOf<Reopen.Ask?>(null) }
    LaunchedEffect(Unit) {
        val library = app.library.await()
        if (fresh) {
            val plan = library.planStart()
            plan.folder?.let { nav.navigate(it.toRoute(reveal = plan.reveal)) }
            when (val reopen = plan.reopen) {
                is Reopen.Now -> nav.navigate(reopen.request.toReaderRoute())
                is Reopen.Ask -> asking = reopen
                null -> Unit
            }
        }
        app.start?.applied(nav.currentBackStackEntry?.id)
        nav.currentBackStackEntryFlow
            .onEach { app.start?.check(it.id) }
            .mapNotNull { it.lastFolder() }
            .distinctUntilChanged()
            .collect { library.launch.setLastFolder(it.folder) }
    }
    asking?.let { reopen ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.reopen_title)) },
            text = { Text(reopen.title) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    nav.navigate(reopen.request.toReaderRoute())
                }) { Text(stringResource(R.string.reopen_open)) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.reopen_skip)) } },
        )
    }
}

/** What the top screen says of the last folder: it is a folder, home clears it, other screens keep it. */
private data class TopFolder(val folder: LastFolder?)

private fun NavBackStackEntry.lastFolder(): TopFolder? = when {
    destination.hasRoute<FolderRoute>() -> toRoute<FolderRoute>().let { TopFolder(LastFolder(LocationId(it.location), it.path, it.documentId)) }
    destination.hasRoute<HomeRoute>() -> TopFolder(null)
    else -> null
}

/** Tells the start in progress that the screen of [entry] has shown its content, once [settles] returns. */
@Composable
internal fun ReportSettled(nav: NavController, entry: NavBackStackEntry, app: HirariApp, settles: suspend () -> Unit) {
    val gate = app.start?.takeUnless { it.done } ?: return
    LaunchedEffect(entry.id) {
        settles()
        gate.settled(entry.id, nav.currentBackStackEntry?.id)
    }
}
