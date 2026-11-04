package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The thumbnail slot (docs/spec.md section 4.4). While the row is bound it collects the cover of [ref], which holds
 * the thumbnail against eviction, and leaving the screen cancels that. A cover in memory shows at once; otherwise a
 * pale square shows, with a progress mark once the wait is long, and [badge] stands in when the cover cannot be made.
 * Binding reads memory only: the store, the decode and the making all run elsewhere. In a list with a [focus], the
 * covers nearest the middle of the screen are made first.
 */
@Composable
internal fun CoverBox(ref: CoverRef?, covers: Covers, badge: CoverBadge?, modifier: Modifier, faded: Boolean = false, focus: CoverFocus? = null) {
    var state by remember(ref) {
        mutableStateOf<CoverState<ImageBitmap>>(ref?.let(covers::peek)?.let { CoverState.Loaded(it) } ?: CoverState.Pending)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var onScreen by remember(ref) { mutableStateOf(false) }
    if (ref != null && (focus != null || onScreen)) LaunchedEffect(ref, focus, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            covers.show(ref, focus).collect { state = it }
        }
    }
    val slotModifier = if (focus != null) modifier else modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        onScreen = bounds.width > 0f && bounds.height > 0f
    }
    val shown = state
    CoverSlot(badge, slotModifier, faded, centered = shown is CoverState.Failed || shown is CoverState.Unavailable) {
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
 * Reads stored covers around the viewport and makes visible covers nearest the middle first (section 4.4).
 * [refs] has one entry per list item, null for one without a cover. Newly visible covers wait until scrolling stops.
 */
@Composable
internal fun PrefetchCovers(refs: List<CoverRef?>, covers: Covers, focus: CoverFocus, scrolling: () -> Boolean = { false }, visible: () -> IntRange) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(refs, covers, focus, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val viewport = covers.viewport(this, focus)
            try {
                snapshotFlow { visible() to scrolling() }.distinctUntilChanged().collect { (range, moving) ->
                    viewport.update(refs, range, moving)
                }
            } finally {
                viewport.clear()
            }
        }
    }
}

private const val FADED_ALPHA = 0.4f

/** How long a cover may take before the slot shows progress, so a cover found in the store does not flash it. */
private const val PROGRESS_DELAY_MS = 300L
