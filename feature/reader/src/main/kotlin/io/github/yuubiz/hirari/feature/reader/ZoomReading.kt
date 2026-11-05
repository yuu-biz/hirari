package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.image.DecodeLimits
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Rotation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MB = 1024L * 1024L

/** Sizes closer than this, in pixels, count as the same. */
private const val SAME_PX = 0.5

/**
 * What reading pages again for a zoom may take (docs/spec.md section 4.8, ピンチと拡大率). [budgetBytes] is the memory
 * budget of section 5.3. [drawn] pages are drawn from a document, as PDF pages are, so they have as many pixels as the
 * screen asks for, where an image has no more than its own.
 */
data class ZoomReads(val budgetBytes: Long = 256 * MB, val drawn: Boolean = false) {
    /** What the zoomed pages of one view may take: a quarter of the budget, as the tiles of a page do (section 3.7). */
    val shareBytes: Long get() = budgetBytes / 4
}

/** How the current view is read again once a zoom settles (docs/spec.md section 4.8, ピンチと拡大率). */
sealed interface ZoomRead {
    /** Its prepared pages already have every pixel the zoom shows. */
    data object None : ZoomRead

    /** Each page whole again at [sizes], of the parts shown, as the prepared view of the zoom. */
    data class Whole(val sizes: List<PixelSize>) : ZoomRead

    /** The part of each page on screen as tiles over its prepared page, since the whole pages would not fit. */
    data object Tiles : ZoomRead
}

/** The whole image of a page as laid out on screen, in pixels, of which a part may show. */
private class FullSize(val width: Double, val height: Double) {
    val bytes: Double get() = width * height * 4
    val longer: Double get() = max(width, height)

    fun sharperThan(other: FullSize): Boolean = width > other.width + SAME_PX || height > other.height + SAME_PX

    operator fun times(k: Double) = FullSize(width * k, height * k)

    fun partSize(part: PagePart) =
        PixelSize((width * part.width).roundToInt().coerceAtLeast(1), (height * part.height).roundToInt().coerceAtLeast(1))
}

/**
 * The read of the current view for [ZoomState.settledFactor] (docs/spec.md section 4.8). Above the scale mode, each
 * page wants its whole image at the zoom, no larger than the image itself unless it is [ZoomReads.drawn]. When that
 * is no sharper than the prepared pages, nothing is read. When every page fits one bitmap and all of them fit
 * [ZoomReads.shareBytes], they are read whole; otherwise the screen's part of them comes as tiles. A turned page has
 * no tiles, so it is read whole at the largest size that fits. 縦に連続 zooms with tiles alone ([tileWindows]).
 */
internal fun ReaderState.planZoomRead(): ZoomRead {
    val factor = zoom.settledFactor
    if (scroll != null || factor <= 1f) return ZoomRead.None
    val view = current
    val zoomed = preparedGeometry(view, factor) ?: return ZoomRead.None
    val prepared = preparedGeometry(view) ?: return ZoomRead.None
    val parts = shownParts(view)
    val wanted = view.pages.indices.map { fullSize(view.pages[it], zoomed.pages[it], parts[it]) }
    val have = view.pages.indices.map { fullSize(view.pages[it], prepared.pages[it], parts[it]) }
    val longest = wanted.maxOf { it.longer }
    val bytes = wanted.sumOf { it.bytes }
    val fits = longest <= DecodeLimits.MAX_BITMAP_SIDE && bytes <= zoomReads.shareBytes
    if (!fits && config.rotation == Rotation.None) {
        return if (wanted.indices.any { wanted[it].sharperThan(have[it]) }) ZoomRead.Tiles else ZoomRead.None
    }
    val k = if (fits) 1.0 else min(DecodeLimits.MAX_BITMAP_SIDE / longest, sqrt(zoomReads.shareBytes / bytes))
    val read = wanted.map { it * k }
    if (read.indices.none { read[it].sharperThan(have[it]) }) return ZoomRead.None
    return ZoomRead.Whole(read.mapIndexed { i, size -> size.partSize(parts[i]) })
}

/** The whole image of [page] when its [part] shows at [rect], no larger than the image unless the pages are drawn. */
private fun ReaderState.fullSize(page: Int, rect: Rect, part: PagePart): FullSize {
    val width = rect.width.toDouble() / part.width
    val height = rect.height.toDouble() / part.height
    val shape = pagination.shapes[page] as PageShape.Known
    return if (zoomReads.drawn) FullSize(width, height) else FullSize(min(width, shape.width.toDouble()), min(height, shape.height.toDouble()))
}

/**
 * Whether a page of [shape] that fills [zoomedWidth] pixels across in 縦に連続 shows more of its pixels than its
 * prepared page, which fills the width of the screen.
 */
internal fun ReaderState.zoomedPastPrepared(shape: PageShape.Known, zoomedWidth: Float): Boolean {
    val cap = if (zoomReads.drawn) Double.MAX_VALUE else shape.width.toDouble()
    return min(zoomedWidth.toDouble(), cap) > min(viewport.width.toDouble(), cap) + SAME_PX
}

/** The whole page whose part [this] shows at [shown], where the parts left out of it would lie. */
internal fun PagePart.wholeAt(shown: Rect): Rect {
    val width = shown.width / this.width
    val height = shown.height / this.height
    val left = shown.left - this.left * width
    val top = shown.top - this.top * height
    return Rect(left, top, left + width, top + height)
}
