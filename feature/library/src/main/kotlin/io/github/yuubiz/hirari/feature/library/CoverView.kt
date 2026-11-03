package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The thumbnail slot (docs/spec.md section 4.4). While the row is bound it collects the cover of [ref], which holds
 * the thumbnail against eviction, and leaving the screen cancels that. A cover in memory shows at once; otherwise a
 * pale square shows, with a progress mark once the wait is long, and [label] stands in when the cover cannot be made.
 * Binding reads memory only: the store, the decode and the making all run elsewhere.
 */
@Composable
internal fun CoverBox(ref: CoverRef?, covers: Covers, label: String?, modifier: Modifier, faded: Boolean = false) {
    var state by remember(ref) {
        mutableStateOf<CoverState<ImageBitmap>>(ref?.let(covers::peek)?.let { CoverState.Loaded(it) } ?: CoverState.Pending)
    }
    if (ref != null) LaunchedEffect(ref) { covers.show(ref).collect { state = it } }
    val shown = state
    CoverSlot(label, modifier, faded, centered = shown is CoverState.Failed || shown is CoverState.Unavailable) {
        when (shown) {
            is CoverState.Loaded -> Image(shown.image, contentDescription = null, Modifier.fillMaxSize().alpha(if (faded) FADED_ALPHA else 1f), contentScale = ContentScale.Fit)
            CoverState.Pending -> if (ref != null) PendingMark(ref)
            CoverState.Failed, CoverState.Unavailable -> Unit
        }
    }
}

@Composable
private fun BoxScope.PendingMark(ref: CoverRef) {
    var waited by remember(ref) { mutableStateOf(false) }
    LaunchedEffect(ref) {
        delay(PROGRESS_DELAY_MS)
        waited = true
    }
    if (waited) CircularProgressIndicator(Modifier.align(Alignment.Center).size(20.dp), strokeWidth = 2.dp)
}

/**
 * Keeps the covers of the items around the screen made and held (docs/spec.md section 4.4), with the nearby
 * priority. [refs] has one entry per item of the list, `null` for an item without a cover; [visible] is the range of
 * items on screen. The window follows it: what stays inside keeps its work, and what leaves is cancelled.
 */
@Composable
internal fun PrefetchCovers(refs: List<CoverRef?>, covers: Covers, visible: () -> IntRange) {
    val scope = rememberCoroutineScope()
    val prefetcher = remember(covers, scope) { covers.prefetcher(scope) }
    DisposableEffect(prefetcher) { onDispose { prefetcher.clear() } }
    LaunchedEffect(refs, prefetcher) {
        snapshotFlow { visible() }.distinctUntilChanged().collect { range ->
            val window = nearbyWindow(range.first, range.last, refs.size)
            prefetcher.update(window.mapNotNullTo(HashSet()) { refs[it] })
        }
    }
}

private const val FADED_ALPHA = 0.4f

/** How long a cover may take before the slot shows progress, so a cover found in the store does not flash it. */
private const val PROGRESS_DELAY_MS = 300L
