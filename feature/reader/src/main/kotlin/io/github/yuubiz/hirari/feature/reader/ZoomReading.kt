package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.image.DecodeLimits
import io.github.yuubiz.hirari.core.image.DecodePlan
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.planDecode
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
 * screen asks for, where an image has no more than its own. [wholeOnly] are the pages that turned out to have no tiles
 * within the share they may take (section 3.7), so a zoom reads them whole as large as fits.
 */
data class ZoomReads(val budgetBytes: Long = 256 * MB, val drawn: Boolean = false, val wholeOnly: Set<Int> = emptySet()) {
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
    val have = view.pages.indices.map { decodedSize(view.pages[it], prepared.pages[it], parts[it]) }
    val longest = wanted.maxOf { it.longer }
    val bytes = wanted.sumOf { it.bytes }
    val fits = longest <= DecodeLimits.MAX_BITMAP_SIDE && bytes <= zoomReads.shareBytes
    val tileable = config.rotation == Rotation.None && view.pages.none { it in zoomReads.wholeOnly }
    if (!fits && tileable) {
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
    if (zoomReads.drawn) return FullSize(width, height)
    val shape = turned(page)
    return FullSize(min(width, shape.width.toDouble()), min(height, shape.height.toDouble()))
}

/**
 * The pixels the prepared page of [page] really has, where [fullSize] has the pixels it is laid out at: the image
 * decoded for that size is no longer than one bitmap holds and, with the pages around it, fits the budget
 * (docs/spec.md section 3.7), and a drawn page takes no more than half the budget (section 3.9).
 */
private fun ReaderState.decodedSize(page: Int, rect: Rect, part: PagePart): FullSize {
    val laid = FullSize(rect.width.toDouble() / part.width, rect.height.toDouble() / part.height)
    if (zoomReads.drawn) {
        val half = zoomReads.budgetBytes / 2.0
        return if (laid.bytes > half) laid * sqrt(half / laid.bytes) else laid
    }
    val shape = turned(page)
    val target = PixelSize(laid.width.roundToInt().coerceAtLeast(1), laid.height.roundToInt().coerceAtLeast(1))
    val plan = planDecode(shape.width, shape.height, target, averagingLimit = Int.MAX_VALUE, bytesPerPixel = 4, zoomReads.budgetBytes)
    return when (plan) {
        is DecodePlan.Decode -> FullSize(plan.output.width.toDouble(), plan.output.height.toDouble())
        is DecodePlan.Refuse -> fullSize(page, rect, part)
    }
}

/** The shape of [page] as shown, which the layout holds turned already when the rotation turns the page. */
private fun ReaderState.turned(page: Int): PageShape.Known = pagination.shapes[page] as PageShape.Known

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
