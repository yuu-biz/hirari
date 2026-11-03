package io.github.yuubiz.hirari.feature.library

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * Shows on [snackbar], one at a time, how the saves ended that no screen has shown yet (docs/spec.md section 4.5),
 * while the screen is resumed. [next] takes the next one, as [DeviceSaves.nextUnshown] does.
 */
@Composable
fun SaveResults(next: suspend () -> SaveFinished, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val take by rememberUpdatedState(next)
    LaunchedEffect(lifecycle, snackbar) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) snackbar.showSnackbar(take().text(resources))
        }
    }
}
