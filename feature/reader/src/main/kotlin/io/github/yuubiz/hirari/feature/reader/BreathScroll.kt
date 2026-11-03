package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.Turn
import kotlin.math.hypot

/** A long sound scrolls at 500 dp per second (docs/spec.md section 4.11, 息での操作). */
const val BREATH_SCROLL_DP_PER_MS = 0.5f

private const val SAME_PX = 0.5f

/**
 * The pan that moves the screen [distancePx] along the stops of the current view toward [turn]: to the next stop past
 * the screen, then on to the one after it. It stops at the last stop of the view, the first going back, and does not
 * turn. `null` when the screen cannot move, as on a view that fits the screen.
 */
fun ReaderState.breathPan(turn: Turn, distancePx: Float): ReaderEvent.Pan? {
    val geometry = geometry() ?: return null
    if (!geometry.overflows) return null
    val path = geometry.stops(config)
    val from = positionOf(geometry, place)
    var at = from
    var left = distancePx
    repeat(path.stops.size) {
        if (left <= 0f) return@repeat
        val target = path.toward(at, turn) ?: return@repeat
        val gap = hypot(target.x - at.x, target.y - at.y)
        if (gap <= left) {
            at = target
            left -= gap
        } else {
            at = Point(at.x + (target.x - at.x) * left / gap, at.y + (target.y - at.y) * left / gap)
            left = 0f
        }
    }
    if (at == from) return null
    return ReaderEvent.Pan(from.x - at.x, from.y - at.y)
}

/** The stop the screen at [position] scrolls to toward [turn], or `null` past the last one that way. */
private fun StopPath.toward(position: Point, turn: Turn): Point? {
    val ahead = nextAfter(position)
    if (turn == Turn.Forward) return ahead?.let { stops[it].point }
    return ((ahead ?: stops.size) - 1 downTo 0).map { stops[it].point }.firstOrNull { hypot(it.x - position.x, it.y - position.y) > SAME_PX }
}
