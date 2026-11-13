package io.github.yuubiz.hirari.core.model

/**
 * The visible command row: keep both ends, when there is a [close], and use the last middle cell for overflow into
 * [more]. Without [more] every command keeps a cell, however narrow. Bounds are dp.
 */
data class CommandCells<T>(val commands: List<T>, val close: T?, val end: T, val more: T?, val bounds: Rect) {
    val cells: List<T> = run {
        val middle = ((bounds.width / 56f).toInt() - if (close == null) 1 else 2).coerceAtLeast(1)
        val items = if (more != null && commands.size > middle) commands.take(middle - 1) + more else commands
        listOfNotNull(close) + items + end
    }
    val cellWidth: Float get() = bounds.width / cells.size

    /** Which cell [point] is in, by position, so two cells holding the same item stay apart. */
    fun indexAt(point: Point): Int? {
        if (point.x < bounds.left || point.x >= bounds.right || point.y < bounds.top || point.y >= bounds.bottom) return null
        return ((point.x - bounds.left) / cellWidth).toInt().takeIf { it in cells.indices }
    }

    fun itemAt(point: Point): T? = indexAt(point)?.let(cells::get)

    /** A slide, including one ending on another cell, cancels the press. */
    fun tapped(origin: Point, released: Point, moved: Boolean): T? =
        indexAt(origin)?.takeIf { !moved && it == indexAt(released) }?.let(cells::get)
}
