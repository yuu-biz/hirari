package io.github.yuubiz.hirari.core.model

import kotlin.math.ceil

data class Point(val x: Float, val y: Float)

data class Size(val width: Float, val height: Float)

data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

enum class StopOrder { RowsFirst, ColumnsFirst }

/**
 * The stop positions for reading [content] through [viewport] (docs/spec.md section 2.6). Each
 * point is the viewport's top-left corner in the coordinate space of [content]. A `Two` view
 * calls this once per page, earlier page first, and concatenates the results.
 */
fun scrollStops(
    content: Rect,
    viewport: Size,
    maxStep: Float,
    order: StopOrder,
    direction: ReadingDirection,
): List<Point> {
    require(maxStep > 0f) { "maxStep $maxStep must be positive" }
    val xs = axisStops(content.left, content.width, viewport.width, maxStep).let {
        if (direction == ReadingDirection.RightToLeft) it.reversed() else it
    }
    val ys = axisStops(content.top, content.height, viewport.height, maxStep)
    return when (order) {
        StopOrder.RowsFirst -> ys.flatMap { y -> xs.map { x -> Point(x, y) } }
        StopOrder.ColumnsFirst -> xs.flatMap { x -> ys.map { y -> Point(x, y) } }
    }
}

/**
 * Float steps such as 0.7 are slightly off, so an exact multiple would gain one more step. The
 * tolerance is a thousandth of a step.
 */
private const val STEP_TOLERANCE = 1e-3

private fun axisStops(start: Float, content: Float, viewport: Float, maxStep: Float): List<Float> {
    val overflow = content.toDouble() - viewport
    val steps = ceil(overflow / (viewport.toDouble() * maxStep) - STEP_TOLERANCE).toInt()
    if (steps <= 0) return listOf(start + (content - viewport) / 2f)
    return (0..steps).map { (start + overflow * it / steps).toFloat() }
}
