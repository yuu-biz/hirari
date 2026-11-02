package io.github.yuubiz.hirari

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import io.github.yuubiz.hirari.core.data.LastFolder
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.feature.library.Reopen
import io.github.yuubiz.hirari.feature.library.StartPlan
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach

/**
 * One start of the app (docs/spec.md section 4.3, 起動時の動作). It lives in the process, not the activity, so an
 * activity rebuilt in the middle of the start takes it up where it was: the plan goes on being made, goes on the
 * back stack once, and the question about the last book stays asked. Main thread only, but [plan].
 */
internal class AppStart(val restoring: Boolean, val plan: Deferred<StartPlan>, finish: () -> Unit) {
    val gate = StartGate(finish)

    /** The plan is on the back stack, which the activity keeps from then on. */
    var applied by mutableStateOf(false)
        private set

    var asking by mutableStateOf<Reopen.Ask?>(null)

    /**
     * The screens the system kept wait for the plan, which may drop them: a kept folder or book that made the app
     * fail must not start loading before then.
     */
    val holding: Boolean get() = restoring && !applied

    fun apply(nav: NavController, plan: StartPlan) {
        if (plan is StartPlan.Open) {
            if (restoring) nav.popBackStack<HomeRoute>(inclusive = false)
            plan.folder?.let { nav.navigate(it.toRoute(reveal = plan.reveal)) }
            when (val reopen = plan.reopen) {
                is Reopen.Now -> nav.navigate(reopen.request.toReaderRoute())
                is Reopen.Ask -> asking = reopen
                null -> Unit
            }
        }
        applied = true
    }
}

/**
 * Starts the app as docs/spec.md section 4.3 (起動時の動作) says: it puts the plan of [start] on the back stack, or
 * asks about the book. Then it keeps the last folder: the folder of the file list last on top, cleared when home
 * comes on top and kept under the other screens.
 */
@Composable
internal fun StartUp(nav: NavController, app: HirariApp, start: AppStart?) {
    LaunchedEffect(Unit) {
        val library = app.library.await()
        if (start != null) {
            if (!start.applied) start.apply(nav, start.plan.await())
            start.gate.applied(nav.currentBackStackEntry?.id)
        }
        nav.currentBackStackEntryFlow
            .onEach { start?.gate?.check(it.id) }
            .mapNotNull { it.lastFolder() }
            .distinctUntilChanged()
            .collect { library.launch.setLastFolder(it.folder) }
    }
    start?.asking?.let { reopen ->
        AlertDialog(
            onDismissRequest = { start.asking = null },
            title = { Text(stringResource(R.string.reopen_title)) },
            text = { Text(reopen.title) },
            confirmButton = {
                TextButton(onClick = {
                    start.asking = null
                    nav.navigate(reopen.request.toReaderRoute())
                }) { Text(stringResource(R.string.reopen_open)) }
            },
            dismissButton = { TextButton(onClick = { start.asking = null }) { Text(stringResource(R.string.reopen_skip)) } },
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

/** A screen that loads a folder or a book, composed only once the start no longer holds kept screens back. */
@Composable
internal fun Kept(app: HirariApp, content: @Composable () -> Unit) {
    if (app.start?.holding != true) content()
}

/** Tells the start in progress that the screen of [entry] has shown its content, once [settles] returns. */
@Composable
internal fun ReportSettled(nav: NavController, entry: NavBackStackEntry, app: HirariApp, settles: suspend () -> Unit) {
    val gate = app.start?.gate?.takeUnless { it.done } ?: return
    LaunchedEffect(entry.id) {
        settles()
        gate.settled(entry.id, nav.currentBackStackEntry?.id)
    }
}
