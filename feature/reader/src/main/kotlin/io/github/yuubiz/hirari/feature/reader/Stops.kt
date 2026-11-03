package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.Motion
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.STOP_SPEED_DP_PER_MS
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.StopOrder
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.model.scrollStops
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToLong

/** Positions closer than this, in pixels, count as the same. */
private const val SAME_PX = 0.5f

/** A stop and the page it reads, counted in reading order within the view. */
data class Stop(val point: Point, val page: Int)

/**
 * The stops of one view in screen pixels (docs/spec.md section 2.6). A view wider than the screen
 * is read page by page. A view that fits across is read as a whole, and every stop keeps the
 * screen inside the view.
 */
data class StopPath(val stops: List<Stop>, val order: StopOrder, val direction: ReadingDirection) {
    init {
        require(stops.isNotEmpty()) { "a view has at least one stop" }
    }

    val last: Int get() = stops.lastIndex

    /** The first stop past [position], the screen's top-left corner, or `null` when none is left. */
    fun nextAfter(position: Point): Int? {
        val page = pageAt(position.x)
        return stops.indices.firstOrNull { index ->
            val stop = stops[index]
            stop.page > page || stop.page == page && isAfter(stop.point, position)
        }
    }

    /** The page whose stops lie nearest to [x] across, the earlier one on a tie. */
    private fun pageAt(x: Float): Int {
        fun outside(stops: List<Stop>): Float {
            val low = stops.minOf { it.point.x }
            val high = stops.maxOf { it.point.x }
            return if (x < low) low - x else if (x > high) x - high else 0f
        }
        return stops.groupBy { it.page }.entries.minWith(compareBy({ outside(it.value) }, { it.key })).key
    }

    private fun isAfter(stop: Point, position: Point): Boolean {
        val ahead = if (direction == ReadingDirection.RightToLeft) position.x - stop.x else stop.x - position.x
        val down = stop.y - position.y
        return when (order) {
            StopOrder.RowsFirst -> down > SAME_PX || abs(down) <= SAME_PX && ahead > SAME_PX
            StopOrder.ColumnsFirst -> ahead > SAME_PX || abs(ahead) <= SAME_PX && down > SAME_PX
        }
    }

    companion object {
        /**
         * [pages] are the page rectangles of the view in screen pixels, in reading order, and
         * [maxStep] is the `stopMaxStepPercent` setting as a fraction.
         */
        fun of(pages: List<Rect>, viewport: Size, maxStep: Float, order: StopOrder, direction: ReadingDirection): StopPath {
            val view = Rect(pages.minOf { it.left }, pages.minOf { it.top }, pages.maxOf { it.right }, pages.maxOf { it.bottom })
            val parts = if (view.width > viewport.width + SAME_PX) pages else listOf(view)
            val stops = parts.flatMapIndexed { page, rect ->
                scrollStops(rect, viewport, maxStep, order, direction).map { Stop(inside(it, view, viewport), page) }
            }
            val distinct = stops.filterIndexed { index, stop -> index == 0 || stop.point != stops[index - 1].point }
            return StopPath(distinct, order, direction)
        }

        private fun inside(point: Point, view: Rect, viewport: Size) =
            Point(axis(point.x, view.left, view.width, viewport.width), axis(point.y, view.top, view.height, viewport.height))

        private fun axis(at: Float, start: Float, content: Float, viewport: Float): Float =
            if (content <= viewport) start + (content - viewport) / 2f else at.coerceIn(start, start + content - viewport)
    }
}

/** Where a stop step leads: another stop in the view, or out of the view toward [Leave.turn]. */
sealed interface StopStep {
    data class Move(val cursor: StopCursor) : StopStep
    data class Leave(val turn: Turn) : StopStep
}

/** The stop the screen rests on, or is moving to. */
data class StopCursor(val path: StopPath, val index: Int) {
    val point: Point get() = path.stops[index].point

    fun step(turn: Turn): StopStep {
        val next = if (turn == Turn.Forward) index + 1 else index - 1
        return if (next in path.stops.indices) StopStep.Move(copy(index = next)) else StopStep.Leave(turn)
    }

    /** After a pan to [position], going forward goes to the nearest stop past it. */
    fun forwardFrom(position: Point): StopStep =
        path.nextAfter(position)?.let { StopStep.Move(copy(index = it)) } ?: StopStep.Leave(Turn.Forward)

    companion object {
        /** A view entered going forward starts at its first stop, and going back at its last. */
        fun entering(path: StopPath, turn: Turn): StopCursor = StopCursor(path, if (turn == Turn.Forward) 0 else path.last)
    }
}

/** The move between two stops at the constant stop speed. */
data class StopMove(val from: Point, val to: Point, val motion: Motion) {
    fun positionAt(nowMs: Long): Point {
        val t = motion.valueAt(nowMs)
        return Point(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
    }

    /** The next input puts the screen on [to] at once. */
    fun interruptedAt(nowMs: Long): StopMove = copy(motion = motion.interruptedAt(nowMs))

    companion object {
        /** [pxPerDp] is the screen density. */
        fun between(from: Point, to: Point, nowMs: Long, pxPerDp: Float): StopMove {
            val dp = hypot(to.x - from.x, to.y - from.y) / pxPerDp
            return StopMove(from, to, Motion(nowMs, ceil(dp / STOP_SPEED_DP_PER_MS).roundToLong()))
        }
    }
}
