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
import io.github.yuubiz.hirari.core.data.LaunchRepository
import io.github.yuubiz.hirari.core.data.OpeningBook
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.logFailure
import io.github.yuubiz.hirari.feature.library.Notice
import io.github.yuubiz.hirari.feature.library.Reopen
import io.github.yuubiz.hirari.feature.library.StartPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import java.io.IOException
import java.util.logging.Logger

/** A failed launch record must not stop navigation or completion of the start. */
internal suspend fun LaunchRepository.recordLastFolder(folder: LastFolder?, debuggable: Boolean = false) = recordLaunch(debuggable) { setLastFolder(folder) }

internal suspend fun LaunchRepository.completeStart(debuggable: Boolean = false) = recordLaunch(debuggable) { finishStart() }

private suspend fun recordLaunch(debuggable: Boolean, write: suspend () -> Unit) {
    try {
        write()
    } catch (error: IOException) {
        Logger.getLogger("AppStart").logFailure("Cannot write launch record", error, debuggable)
    }
}

/**
 * One start of the app (docs/spec.md section 4.3, 起動時の動作). It lives in the process, not the activity, so an
 * activity rebuilt in the middle of the start takes it up where it was: the plan goes on being made, goes on the
 * back stack once, and the question about the last book stays asked. Main thread only, but [plan].
 */
internal class AppStart(val restoring: Boolean, val plan: Deferred<StartPlan>, scope: CoroutineScope, finish: () -> Unit) {
    val watch = StartWatch(scope, finish)
    val gate = StartGate(watch::settled)
    val kept = KeptScreens(restoring)

    /** The plan is on the back stack, which the activity keeps from then on. */
    var applied by mutableStateOf(false)
        private set

    var asking by mutableStateOf<Reopen.Ask?>(null)

    /** What home says once it shows, taken when it is shown. */
    var notice by mutableStateOf<Notice?>(null)

    /** The book the app ended while opening, named until the user closes the notice (docs/spec.md section 6.1). */
    var ended by mutableStateOf<OpeningBook?>(null)

    fun apply(nav: NavController, plan: StartPlan) {
        val died = plan.diedOpening
        kept.decide(adopt = plan is StartPlan.Restore, dropReaders = died != null)
        if (restoring && plan !is StartPlan.Restore) nav.popBackStack<HomeRoute>(inclusive = false)
        when (plan) {
            is StartPlan.Restore -> if (died != null) nav.popBackStack<ReaderRoute>(inclusive = true)
            StartPlan.Unplanned -> notice = Notice.StartUnplanned
            is StartPlan.Open -> {
                plan.folder?.let { nav.navigate(it.toRoute(reveal = plan.reveal)) }
                when (val reopen = plan.reopen) {
                    is Reopen.Now -> nav.navigate(reopen.request.toReaderRoute())
                    is Reopen.Ask -> asking = reopen
                    null -> Unit
                }
            }
        }
        val asked = (plan as? StartPlan.Open)?.reopen as? Reopen.Ask
        ended = died?.takeUnless { asked?.suspect == true && it.isBook(asked.request.book) }
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
            .collect { library.launch.recordLastFolder(it.folder, app.debuggable) }
    }
    val ended = start?.ended
    if (ended != null) {
        AlertDialog(
            onDismissRequest = { start.ended = null },
            title = { Text(stringResource(R.string.opening_ended_title)) },
            text = { Text(stringResource(R.string.opening_ended_text, ended.name)) },
            confirmButton = { TextButton(onClick = { start.ended = null }) { Text(stringResource(R.string.opening_ended_close)) } },
        )
    }
    start?.asking?.takeIf { ended == null }?.let { reopen ->
        AlertDialog(
            onDismissRequest = { start.asking = null },
            title = { Text(stringResource(R.string.reopen_title)) },
            text = { Text(if (reopen.suspect) stringResource(R.string.reopen_after_ending, reopen.title) else reopen.title) },
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

/** The screen of [entry], which loads a folder or a book, or is the image [reader], composed only when the start lets it load. */
@Composable
internal fun Kept(app: HirariApp, entry: NavBackStackEntry, reader: Boolean = false, content: @Composable () -> Unit) {
    if (app.start?.kept?.loads(entry.id, reader) != false) content()
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
