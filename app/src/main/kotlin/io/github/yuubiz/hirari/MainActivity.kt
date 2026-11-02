package io.github.yuubiz.hirari

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.ui.HirariTheme
import io.github.yuubiz.hirari.feature.library.FolderActions
import io.github.yuubiz.hirari.feature.library.FolderContent
import io.github.yuubiz.hirari.feature.library.FolderScreen
import io.github.yuubiz.hirari.feature.library.FolderViewModel
import io.github.yuubiz.hirari.feature.library.HomeActions
import io.github.yuubiz.hirari.feature.library.HomeScreen
import io.github.yuubiz.hirari.feature.library.HomeViewModel
import io.github.yuubiz.hirari.feature.library.Loadable
import io.github.yuubiz.hirari.feature.reader.KeyRelay
import io.github.yuubiz.hirari.feature.reader.ReaderPhase
import io.github.yuubiz.hirari.feature.reader.ReaderScreen
import io.github.yuubiz.hirari.feature.reader.ReaderViewModel
import io.github.yuubiz.hirari.feature.settings.LicenseScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ZIP and CBZ as providers label them (docs/spec.md section 3.1). */
private val DOCUMENT_TYPES = arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream")

class MainActivity : ComponentActivity() {
    private val keys = KeyRelay()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as HirariApp
        val debugStart = prototypeStartRoute(intent) ?: debugBookRoute(intent)
        val fresh = savedInstanceState == null && debugStart == null
        if (fresh) app.start = StartGate { app.appScope.launch { app.library.await().launch.finishStart() } }
        setContent {
            HirariTheme {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                fun open(uri: Uri?, folder: Boolean) {
                    if (uri == null) return
                    scope.launch {
                        withContext(Dispatchers.IO) { keepPermission(uri) }
                        val book = UriString(uri.toString()).let { if (folder) BookAddress.Tree(it) else BookAddress.Document(it) }
                        nav.navigate(OpenBookRequest(book).toReaderRoute())
                    }
                }
                val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { open(it, folder = false) }
                val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { open(it, folder = true) }
                NavHost(nav, startDestination = debugStart ?: HomeRoute) {
                    composable<HomeRoute> { entry ->
                        val home = viewModel { HomeViewModel(app.library) }
                        ReportSettled(nav, entry, app) { home.state.first { it.shelf is Loadable.Ready } }
                        HomeScreen(
                            home,
                            HomeActions(
                                openBook = { nav.navigate(it.toReaderRoute()) },
                                openFolder = { nav.navigate(it.toRoute()) },
                                menu = {
                                    HomeMenu(
                                        onOpenFile = { pickDocument.launch(DOCUMENT_TYPES) },
                                        onOpenFolder = { pickFolder.launch(null) },
                                        onLicenses = { nav.navigate(LicensesRoute) },
                                        onOpen = { nav.navigate(it) },
                                    )
                                },
                            ),
                        )
                    }
                    composable<FolderRoute> { entry ->
                        val route = entry.toRoute<FolderRoute>()
                        val key = route.toKey()
                        val folder = viewModel { FolderViewModel(app.library, key) }
                        ReportSettled(nav, entry, app) { folder.state.first { it.content is FolderContent.Shown || it.content is FolderContent.Failed } }
                        FolderScreen(
                            folder,
                            FolderActions(
                                openBook = { nav.navigate(it.toReaderRoute()) },
                                openFolder = { nav.navigate(it.toRoute()) },
                                openAncestor = { nav.goUpTo(key.copy(path = it, documentId = null), from = key.path) },
                                close = { nav.popBackStack() },
                            ),
                            reveal = route.reveal,
                        )
                    }
                    composable<LicensesRoute> { LicenseScreen(onBack = { nav.popBackStack() }) }
                    composable<ReaderRoute> { entry ->
                        val request = entry.toRoute<ReaderRoute>().toRequest()
                        val reader = viewModel { ReaderViewModel(app, app.services, request) }
                        ReportSettled(nav, entry, app) { snapshotFlow { reader.phase }.first { it != ReaderPhase.Opening } }
                        ReaderScreen(reader, keys, onClose = { if (!nav.popBackStack()) finish() })
                    }
                    prototypeRoutes()
                }
                StartUp(nav, app, fresh)
            }
        }
    }

    /**
     * Before Compose focus handling, which would take the D-pad keys first. Lint flags androidx's own override of
     * this public `Activity` method as restricted.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.dispatch(event) || super.dispatchKeyEvent(event)

    /** docs/spec.md section 2.7: the position is always written out on `onStop`. */
    override fun onStop() {
        super.onStop()
        val app = application as HirariApp
        app.appScope.launch { app.services.await().progress.flush() }
    }

    /**
     * A picked book opens again after a restart, so keep its grant (docs/spec.md section 3.1). It is a Binder call
     * to the system, so it runs off the main thread (section 6.4).
     */
    private fun keepPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }
}

/**
 * A book opens without registering a place from here (docs/spec.md section 4.2). Until the settings screen
 * exists, the license screen is opened from here too (M0).
 */
@Composable
private fun HomeMenu(onOpenFile: () -> Unit, onOpenFolder: () -> Unit, onLicenses: () -> Unit, onOpen: (Any) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.menu)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_open_file)) },
            onClick = {
                open = false
                onOpenFile()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_open_folder)) },
            onClick = {
                open = false
                onOpenFolder()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_licenses)) },
            onClick = {
                open = false
                onLicenses()
            },
        )
        PrototypeMenuItem {
            open = false
            onOpen(it)
        }
    }
}
