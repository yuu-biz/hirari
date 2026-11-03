package io.github.yuubiz.hirari.core.model

import kotlin.math.roundToInt

/**
 * The part of a page the user chose as the cover of a book (docs/spec.md section 4.4, 範囲を選んで表紙にする). The edges
 * are whole numbers out of [SCALE] of the page's width (left and right) and height (top and bottom), counted in the
 * image as the file stores it after its EXIF rotation, before any rotation, trimming or correction the reader
 * applies. Whole numbers keep the thumbnail key stable: the same range always writes the same text.
 *
 * Make one with [of] or [ofFractions] when the edges come from a drag; they put the edges in order and inside the
 * page and keep each side at least [MIN_SIDE] long.
 */
data class CoverCrop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left in 0..SCALE && right in 0..SCALE && top in 0..SCALE && bottom in 0..SCALE) { "outside the page: $this" }
        require(right - left >= MIN_SIDE && bottom - top >= MIN_SIDE) { "smaller than $MIN_SIDE: $this" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** The text the database and the thumbnail key hold: `left,top,right,bottom`. */
    fun encode(): String = "$left,$top,$right,$bottom"

    companion object {
        const val SCALE = 10_000

        /** The least length of a side: a tenth of the page. */
        const val MIN_SIDE = 1_000

        /** The range of these edges, or `null` when [text] is not one written by [encode]. */
        fun decode(text: String): CoverCrop? {
            val edges = text.split(',').map { it.trim().toIntOrNull() ?: return null }
            if (edges.size != 4) return null
            val (left, top, right, bottom) = edges
            if (listOf(left, top, right, bottom).any { it !in 0..SCALE } || right - left < MIN_SIDE || bottom - top < MIN_SIDE) return null
            return CoverCrop(left, top, right, bottom)
        }

        fun of(left: Int, top: Int, right: Int, bottom: Int): CoverCrop {
            val (x0, x1) = side(left, right)
            val (y0, y1) = side(top, bottom)
            return CoverCrop(x0, y0, x1, y1)
        }

        /** [of] for edges given as fractions of the page. */
        fun ofFractions(left: Float, top: Float, right: Float, bottom: Float): CoverCrop =
            of((left * SCALE).roundToInt(), (top * SCALE).roundToInt(), (right * SCALE).roundToInt(), (bottom * SCALE).roundToInt())

        /**
         * One side from two edges that may be in either order or outside the page: inside [0, SCALE], at least
         * [MIN_SIDE] long, and when it has to grow, grown around its middle and slid back inside the page.
         */
        private fun side(a: Int, b: Int): Pair<Int, Int> {
            var low = minOf(a, b).coerceIn(0, SCALE)
            var high = maxOf(a, b).coerceIn(0, SCALE)
            if (high - low < MIN_SIDE) {
                val middle = (low + high) / 2
                low = (middle - MIN_SIDE / 2).coerceIn(0, SCALE - MIN_SIDE)
                high = low + MIN_SIDE
            }
            return low to high
        }
    }
}
