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
import io.github.yuubiz.hirari.feature.library.HomeScreen
import io.github.yuubiz.hirari.feature.reader.KeyRelay
import io.github.yuubiz.hirari.feature.reader.ReaderScreen
import io.github.yuubiz.hirari.feature.reader.ReaderViewModel
import io.github.yuubiz.hirari.feature.settings.LicenseScreen
import kotlinx.coroutines.Dispatchers
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
                NavHost(nav, startDestination = prototypeStartRoute(intent) ?: debugBookRoute(intent) ?: HomeRoute) {
                    composable<HomeRoute> {
                        HomeScreen(
                            onOpenFile = { pickDocument.launch(DOCUMENT_TYPES) },
                            onOpenFolder = { pickFolder.launch(null) },
                            actions = { HomeMenu(onLicenses = { nav.navigate(LicensesRoute) }, onOpen = { nav.navigate(it) }) },
                        )
                    }
                    composable<LicensesRoute> { LicenseScreen(onBack = { nav.popBackStack() }) }
                    composable<ReaderRoute> { entry ->
                        val request = entry.toRoute<ReaderRoute>().toRequest()
                        val reader = viewModel { ReaderViewModel(app, app.services, request) }
                        ReaderScreen(reader, keys, onClose = { if (!nav.popBackStack()) finish() })
                    }
                    prototypeRoutes()
                }
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

/** Until the settings screen exists, the license screen is opened from here (docs/spec.md M0). */
@Composable
private fun HomeMenu(onLicenses: () -> Unit, onOpen: (Any) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.menu)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
