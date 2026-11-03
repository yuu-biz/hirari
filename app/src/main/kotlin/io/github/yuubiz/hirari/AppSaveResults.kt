package io.github.yuubiz.hirari

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.yuubiz.hirari.feature.library.SaveResults

/**
 * How the saves ended, over whatever screen is shown (docs/spec.md section 4.5). The file list shows them itself,
 * above its bar of the saves under way, so this steps aside while it is on top.
 */
@Composable
fun AppSaveResults(nav: NavController, app: HirariApp) {
    val top by nav.currentBackStackEntryAsState()
    if (top?.destination?.hasRoute<FolderRoute>() == true) return
    val snackbar = remember { SnackbarHostState() }
    SaveResults({ app.library.await().saves.nextUnshown() }, snackbar)
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snackbar) }
}
