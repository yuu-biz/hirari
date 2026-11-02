package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.image.MarginScan
import io.github.yuubiz.hirari.core.image.PageTrim
import io.github.yuubiz.hirari.core.image.PixelRect
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.trimPair
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.core.model.TrimStrength
import io.github.yuubiz.hirari.core.model.side
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A part of a page as turned, in fractions of its width and height from its top-left corner. */
data class PagePart(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun half(side: Side): PagePart {
        val middle = (left + right) / 2f
        return if (side == Side.Left) copy(right = middle) else copy(left = middle)
    }

    /** This part of a bitmap of [size], at least one pixel on each side. */
    fun pixelsOf(size: PixelSize): PixelRect {
        val x = (left * size.width).roundToInt().coerceIn(0, size.width - 1)
        val y = (top * size.height).roundToInt().coerceIn(0, size.height - 1)
        val right = (right * size.width).roundToInt().coerceIn(x + 1, size.width)
        val bottom = (bottom * size.height).roundToInt().coerceIn(y + 1, size.height)
        return PixelRect(x, y, right, bottom)
    }

    /** The size of this part of a page of [size], at least one pixel on each side. */
    fun sizeOf(size: PixelSize): PixelSize =
        PixelSize((size.width * width).roundToInt().coerceAtLeast(1), (size.height * height).roundToInt().coerceAtLeast(1))

    companion object {
        val Whole = PagePart(0f, 0f, 1f, 1f)

        /** What [trim] keeps of its page. */
        fun of(trim: PageTrim): PagePart {
            val content = trim.content
            val page = trim.page
            return PagePart(
                content.left.toFloat() / page.width,
                content.top.toFloat() / page.height,
                content.right.toFloat() / page.width,
                content.bottom.toFloat() / page.height,
            )
        }
    }
}

/** The pixels a page of this shape keeps of [part], which [ViewGeometry] lays out in place of the whole page. */
fun PageShape.Known.kept(part: PagePart): PageShape.Known = sizeOf(part).let { PageShape.Known(it.width, it.height) }

private fun PageShape.Known.sizeOf(part: PagePart): PixelSize = part.sizeOf(PixelSize(width, height))

/**
 * What 余白トリミング keeps of each page of [view], in reading order (docs/spec.md section 4.8). A half keeps its
 * half of what the whole page keeps. Every page is whole while the strength is off and until the margins of every
 * page of the view are known, so the two pages of a pair are trimmed together or not at all.
 */
fun ReaderState.keptParts(view: PageView): List<PagePart> {
    val whole = view.pages.map { PagePart.Whole }
    if (config.trim == TrimStrength.Off) return whole
    val trims = view.pages.map { margins[it]?.trim(config.trim) ?: return whole }
    val settled = if (view is PageView.Two) trimPair(trims[0], trims[1]).toList() else trims
    return settled.map(PagePart::of)
}

/** The part of the turned page each page of [view] shows: what it keeps, and for a half, its half of that. */
fun ReaderState.shownParts(view: PageView): List<PagePart> {
    val kept = keptParts(view)
    return if (view is PageView.Half) listOf(kept.single().half(view.part.side(config.direction))) else kept
}

/**
 * The margins of [page] learned, at most [MARGINS_HELD] pages, the farthest from [here] dropped first. A page
 * already held keeps its first scan: both halves of a wide page read it on their first visit.
 */
internal fun Map<Int, MarginScan>.learned(page: Int, scan: MarginScan, here: Int): Map<Int, MarginScan> {
    if (page in this) return this
    val next = this + (page to scan)
    if (next.size <= MARGINS_HELD) return next
    return next.entries.sortedBy { abs(it.key - here) }.take(MARGINS_HELD).associate { it.key to it.value }
}

/** About 10 KB a page, so a long book holds at most a few megabytes of margins (docs/spec.md section 4.8). */
internal const val MARGINS_HELD = 256

/** The pixels of a bitmap to draw, and where on screen. */
data class PageDraw(val source: PixelRect, val destination: Rect)

/**
 * How a bitmap of [bitmap] pixels that shows [covered] of its page draws into [rect], which shows [wanted] of the
 * same page: the part both share, at the same scale on each side, so a page prepared for another trim draws
 * undistorted until its replacement arrives. `null` when they share nothing.
 */
fun pageDraw(wanted: PagePart, covered: PagePart, bitmap: PixelSize, rect: Rect): PageDraw? {
    val shared = PagePart(max(wanted.left, covered.left), max(wanted.top, covered.top), min(wanted.right, covered.right), min(wanted.bottom, covered.bottom))
    if (shared.width <= 0f || shared.height <= 0f) return null
    val inCovered = PagePart(
        (shared.left - covered.left) / covered.width,
        (shared.top - covered.top) / covered.height,
        (shared.right - covered.left) / covered.width,
        (shared.bottom - covered.top) / covered.height,
    )
    val destination = Rect(
        rect.left + (shared.left - wanted.left) / wanted.width * rect.width,
        rect.top + (shared.top - wanted.top) / wanted.height * rect.height,
        rect.left + (shared.right - wanted.left) / wanted.width * rect.width,
        rect.top + (shared.bottom - wanted.top) / wanted.height * rect.height,
    )
    return PageDraw(inCovered.pixelsOf(bitmap), destination)
}
