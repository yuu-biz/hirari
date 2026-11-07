package io.github.yuubiz.hirari.core.model

/** The visible command row: keep both ends and use the last middle cell for overflow. Bounds are dp. */
data class CommandCells<T>(val commands: List<T>, val close: T, val end: T, val more: T, val bounds: Rect) {
    val cells: List<T> = run {
        val middle = ((bounds.width / 56f).toInt() - 2).coerceAtLeast(1)
        val items = if (commands.size > middle) commands.take(middle - 1) + more else commands
        listOf(close) + items + end
    }
    val cellWidth: Float get() = bounds.width / cells.size

    fun itemAt(point: Point): T? {
        if (point.x < bounds.left || point.x >= bounds.right || point.y < bounds.top || point.y >= bounds.bottom) return null
        return cells.getOrNull(((point.x - bounds.left) / cellWidth).toInt())
    }

    /** A slide, including one ending on another cell, cancels the press. */
    fun tapped(origin: Point, released: Point, moved: Boolean): T? = itemAt(origin)?.takeIf { !moved && it == itemAt(released) }
}
