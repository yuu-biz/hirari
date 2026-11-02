package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.data.Storage
import androidx.compose.runtime.produceState
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.LaunchedEffect
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
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.ui.HirariTheme
import io.github.yuubiz.hirari.feature.library.ArchiveActions
import io.github.yuubiz.hirari.feature.library.ArchiveScreen
import io.github.yuubiz.hirari.feature.library.ArchiveUi
import io.github.yuubiz.hirari.feature.library.ArchiveViewModel
import io.github.yuubiz.hirari.feature.library.FolderActions
import io.github.yuubiz.hirari.feature.library.FolderContent
import io.github.yuubiz.hirari.feature.library.FolderScreen
import io.github.yuubiz.hirari.feature.library.FolderViewModel
import io.github.yuubiz.hirari.feature.library.HomeActions
import io.github.yuubiz.hirari.feature.library.HomeScreen
import io.github.yuubiz.hirari.feature.library.HomeViewModel
import io.github.yuubiz.hirari.feature.library.Loadable
import io.github.yuubiz.hirari.feature.library.Notice
import io.github.yuubiz.hirari.feature.library.SmbServerScreen
import io.github.yuubiz.hirari.feature.library.SmbServerViewModel
import io.github.yuubiz.hirari.feature.reader.KeyRelay
import io.github.yuubiz.hirari.feature.reader.ReaderPhase
import io.github.yuubiz.hirari.feature.reader.ReaderScreen
import io.github.yuubiz.hirari.feature.reader.ReaderViewModel
import io.github.yuubiz.hirari.feature.settings.LicenseScreen
import io.github.yuubiz.hirari.feature.text.TextKeyRelay
import io.github.yuubiz.hirari.feature.text.TextReaderScreen
import io.github.yuubiz.hirari.feature.text.TextReaderUiState
import io.github.yuubiz.hirari.feature.text.TextReaderViewModel
import io.github.yuubiz.hirari.feature.settings.SettingsScreen
import io.github.yuubiz.hirari.feature.library.planStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ZIP and CBZ as providers label them (docs/spec.md section 3.1). */
private val DOCUMENT_TYPES = arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream")

class MainActivity : ComponentActivity() {
    private val keys = KeyRelay()
    private val textKeys = TextKeyRelay()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as HirariApp
        val debugStart = prototypeStartRoute(intent) ?: debugBookRoute(intent) ?: intent.incoming()?.toRoute()
        val kind = startKind(savedState = savedInstanceState != null, startInProcess = app.start != null)
        when {
            debugStart != null -> app.start = null
            kind != StartKind.Recreated -> {
                val restoring = kind == StartKind.Restored
                val plan = app.appScope.async { app.library.await().planStart(restoring) }
                app.start = AppStart(restoring, plan) { app.appScope.launch { app.library.await().launch.finishStart() } }
            }
        }
        setContent {
            HirariTheme {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                var homeNotice by remember { mutableStateOf<Notice?>(null) }
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
                        LaunchedEffect(homeNotice) {
                            homeNotice?.let(home::show)
                            homeNotice = null
                        }
                        ReportSettled(nav, entry, app) { home.state.first { it.shelf is Loadable.Ready } }
                        HomeScreen(
                            home,
                            HomeActions(
                                openBook = { nav.navigate(it.toReaderRoute()) },
                                openFolder = { nav.navigate(it.toRoute()) },
                                editServer = { nav.navigate(SmbServerRoute(it?.value)) },
                                menu = {
                                    HomeMenu(
                                        onOpenFile = { pickDocument.launch(DOCUMENT_TYPES) },
                                        onOpenFolder = { pickFolder.launch(null) },
                                        onSettings = { nav.navigate(SettingsRoute) },
                                        onOpen = { nav.navigate(it) },
                                    )
                                },
                            ),
                        )
                    }
                    composable<FolderRoute> { entry ->
                        Kept(app, entry) {
                            val route = entry.toRoute<FolderRoute>()
                            val key = route.toKey()
                            val folder = viewModel { FolderViewModel(app.library, key) }
                            ReportSettled(nav, entry, app) { folder.state.first { it.content is FolderContent.Shown || it.content is FolderContent.Failed } }
                            FolderScreen(
                                folder,
                                FolderActions(
                                    openBook = { nav.navigate(it.toReaderRoute()) },
                                    openText = { nav.navigate(it.toRoute()) },
                                    openArchive = { nav.navigate(it.toRoute()) },
                                    openFolder = { nav.navigate(it.toRoute()) },
                                    pinShortcut = { pinShortcut(it) },
                                    openAncestor = { nav.goUpTo(key.copy(path = it, documentId = null), from = key.path) },
                                    close = { nav.popBackStack() },
                                ),
                                reveal = route.reveal,
                            )
                        }
                    }
                    composable<IncomingRoute> { entry ->
                        IncomingDocument(entry.toRoute<IncomingRoute>(), nav, contentResolver)
                    }
                    composable<ShortcutRoute> { entry ->
                        ShortcutOpen(entry.toRoute<ShortcutRoute>(), nav, app) { homeNotice = Notice.ShortcutMissing }
                    }
                    composable<SmbServerRoute> { entry ->
                        ReportSettled(nav, entry, app) {}
                        val id = entry.toRoute<SmbServerRoute>().location?.let(::LocationId)
                        val form = viewModel { SmbServerViewModel(app.library, id) }
                        SmbServerScreen(form, onClose = { nav.popBackStack() })
                    }
                    composable<ArchiveRoute> { entry ->
                        Kept(app, entry) {
                            val key = entry.toRoute<ArchiveRoute>().toKey()
                            val archive = viewModel { ArchiveViewModel(app.library, key) }
                            ReportSettled(nav, entry, app) { archive.state.first { it.content !is ArchiveUi.Loading } }
                            ArchiveScreen(
                                archive,
                                ArchiveActions(
                                    openBook = { nav.navigate(it.toReaderRoute()) },
                                    openText = { nav.navigate(it.toRoute()) },
                                    close = { nav.popBackStack() },
                                ),
                            )
                        }
                    }
                    composable<LicensesRoute> { entry ->
                        ReportSettled(nav, entry, app) {}
                        LicenseScreen(onBack = { nav.popBackStack() })
                    }
                    composable<SettingsRoute> { entry ->
                        ReportSettled(nav, entry, app) { app.services.await() }
                        val storage by produceState<Storage?>(null) { value = app.services.await().storage }
                        storage?.let { SettingsScreen(it, onBack = { nav.popBackStack() }, onOpenLicenses = { nav.navigate(LicensesRoute) }) }
                    }
                    composable<ReaderRoute> { entry ->
                        Kept(app, entry) {
                            val request = entry.toRoute<ReaderRoute>().toRequest()
                            val reader = viewModel { ReaderViewModel(app, app.services, request) }
                            ReportSettled(nav, entry, app) { snapshotFlow { reader.showsContent() }.first { it } }
                            ReaderScreen(
                                reader,
                                keys,
                                onClose = { if (!nav.popBackStack()) finish() },
                                onOpenBook = { nav.navigate(it.toReaderRoute()) { popUpTo<ReaderRoute> { inclusive = true } } },
                            )
                        }
                    }
                    composable<TextRoute> { entry ->
                        Kept(app, entry) {
                            val ref = entry.toRoute<TextRoute>().toRef()
                            val reader = viewModel(key = ref.toString()) { TextReaderViewModel(application, app.text, ref) }
                            ReportSettled(nav, entry, app) { reader.state.first { it !is TextReaderUiState.Opening } }
                            TextReaderScreen(
                                reader,
                                textKeys,
                                onBack = { if (!nav.popBackStack()) finish() },
                                onOpenSettings = { nav.navigate(SettingsRoute) },
                            )
                        }
                    }
                    prototypeRoutes()
                }
                StartUp(nav, app, app.start)
            }
        }
    }

    /** What the intent that started the activity hands over (docs/spec.md section 4.11). */
    private fun Intent.incoming(): Incoming? = incoming(
        action = action,
        data = dataString,
        stream = streamUri()?.toString(),
        location = if (hasExtra(EXTRA_LOCATION)) getLongExtra(EXTRA_LOCATION, 0) else null,
        path = getStringExtra(EXTRA_PATH),
        folder = getBooleanExtra(EXTRA_FOLDER, false),
    )

    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    /**
     * Before Compose focus handling, which would take the D-pad keys first. Lint flags androidx's own override of
     * this public `Activity` method as restricted.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.dispatch(event) || textKeys.dispatch(event) || super.dispatchKeyEvent(event)

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
 * The reader shows the pages of its first view, or their failures, or why the book did not open (docs/spec.md
 * section 4.3, 起動時の動作). A book that opened has not shown anything yet; its first decode may still fail.
 */
private fun ReaderViewModel.showsContent(): Boolean = when (phase) {
    ReaderPhase.Opening -> false
    ReaderPhase.Reading -> prepared.let { state?.let { preparedView(it.current) } != null }
    is ReaderPhase.Failed, ReaderPhase.NoPages -> true
}

/** A book opens without registering a place from here (docs/spec.md section 4.2). */
@Composable
private fun HomeMenu(onOpenFile: () -> Unit, onOpenFolder: () -> Unit, onSettings: () -> Unit, onOpen: (Any) -> Unit) {
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
            text = { Text(stringResource(R.string.menu_settings)) },
            onClick = {
                open = false
                onSettings()
            },
        )
        PrototypeMenuItem {
            open = false
            onOpen(it)
        }
    }
}
