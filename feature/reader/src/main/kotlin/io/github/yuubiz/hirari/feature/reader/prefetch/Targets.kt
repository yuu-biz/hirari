package io.github.yuubiz.hirari.feature.reader.prefetch

import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.side
import io.github.yuubiz.hirari.feature.reader.ReaderState
import kotlin.math.roundToInt

/**
 * The sizes [view] is prepared at: its pages on screen at the scale of the scale mode. A pinch stretches them
 * until tiles arrive (docs/spec.md section 4.8, ピンチと拡大率).
 */
fun ReaderState.prefetchTarget(view: PageView): ViewTarget? {
    val geometry = preparedGeometry(view) ?: return null
    val sizes = geometry.pages.map { PixelSize(it.width.roundToInt().coerceAtLeast(1), it.height.roundToInt().coerceAtLeast(1)) }
    return ViewTarget(view, sizes, (view as? PageView.Half)?.part?.side(config.direction))
}

/** Every view on screen: the current one, both views of a turn or a return, and the neighbor a finger pulls in. */
val ReaderState.shownViews: Set<PageView>
    get() = buildSet {
        add(current)
        transition?.let {
            add(it.outgoing)
            add(it.incoming)
        }
        pull?.neighbor?.let(::add)
    }

fun ReaderState.prefetchInput(paused: Boolean = false): PrefetchInput = PrefetchInput(
    views = pagination.views,
    current = pagination.currentIndex,
    shapes = pagination.shapes,
    travel = travelDirection(recentTurns),
    targets = ::prefetchTarget,
    shown = shownViews,
    paused = paused,
)
