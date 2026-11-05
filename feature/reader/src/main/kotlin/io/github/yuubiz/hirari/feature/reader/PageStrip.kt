package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.PageShape
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** The thumbnail strip of the page panel (docs/spec.md section 4.8, ページバー). Pages are counted from 0. */
internal object PageStrip {
    const val CELL_HEIGHT_DP = 160f

    /** In landscape a cell is at most this share of the screen's height. */
    const val LANDSCAPE_HEIGHT_SHARE = 0.35f

    /** The width of a cell for an upright page, to its height: the side ratio of A-series paper. */
    const val UPRIGHT_ASPECT = 0.707f

    /** A wide page's cell is at most this many upright cells wide. */
    const val WIDEST = 2f

    /** The gap between two cells. */
    const val GAP_DP = 8f

    /** The pages past each end of the strip on screen whose thumbnails are made, so a short scroll finds them. */
    const val MARGIN_PAGES = 2

    /** What the thumbnails of the strip and the page bar may hold together. */
    const val THUMBNAIL_BYTES = 24L * 1024 * 1024

    fun cellHeightDp(screenWidthDp: Float, screenHeightDp: Float): Float =
        if (screenWidthDp > screenHeightDp) min(CELL_HEIGHT_DP, screenHeightDp * LANDSCAPE_HEIGHT_SHARE) else CELL_HEIGHT_DP

    /** The width of [shape]'s cell: an upright cell, or as wide as the page's sides give up to [WIDEST] of those. */
    fun cellWidthDp(shape: PageShape, heightDp: Float): Float {
        val upright = heightDp * UPRIGHT_ASPECT
        if (shape !is PageShape.Known || shape.height <= 0 || shape.width <= shape.height) return upright
        return (heightDp * shape.width / shape.height).coerceIn(upright, upright * WIDEST)
    }

    /** The box a thumbnail is decoded into, in pixels: the widest cell of the tallest strip. */
    fun decodeSize(density: Float): Pair<Int, Int> {
        val height = CELL_HEIGHT_DP * density
        return (height * UPRIGHT_ASPECT * WIDEST).roundToInt() to height.roundToInt()
    }

    /**
     * The page whose cell is nearest the middle of the strip on screen, of [cells] laid out from [viewportStart]
     * to [viewportEnd], or `null` when none is laid out.
     */
    fun centre(cells: List<Cell>, viewportStart: Int, viewportEnd: Int): Int? {
        val middle = (viewportStart + viewportEnd) / 2f
        return cells.minByOrNull { abs(it.offset + it.size / 2f - middle) }?.page
    }

    /** [pages] in the order their thumbnails are made: [centre] first, then outward, the later page first at each step. */
    fun madeOrder(centre: Int, pages: Collection<Int>): List<Int> =
        pages.distinct().sortedWith(compareBy({ abs(it - centre) }, { if (it >= centre) 0 else 1 }))

    /** A cell laid out on the strip: [offset] and [size] along it, in pixels. */
    data class Cell(val page: Int, val offset: Int, val size: Int)
}

/**
 * The thumbnails of a book, held within [budget] bytes, the least recently used dropped first. One thumbnail larger
 * than the budget is still held, alone. Not thread-safe.
 */
internal class ThumbnailCache<T : Any>(private val budget: Long, private val bytes: (T) -> Long) {
    private val entries = LinkedHashMap<Int, T>(16, 0.75f, true)
    private var total = 0L

    val heldBytes: Long get() = total

    operator fun get(page: Int): T? = entries[page]

    operator fun set(page: Int, value: T) {
        entries.put(page, value)?.let { total -= bytes(it) }
        total += bytes(value)
        val oldest = entries.entries.iterator()
        while (total > budget && entries.size > 1) {
            val eldest = oldest.next()
            if (eldest.key == page) continue
            total -= bytes(eldest.value)
            oldest.remove()
        }
    }

    fun clear() {
        entries.clear()
        total = 0
    }
}
