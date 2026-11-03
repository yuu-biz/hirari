package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.Turn
import kotlin.math.hypot

/** A long sound scrolls at 500 dp per second (docs/spec.md section 4.11, 息での操作). */
const val BREATH_SCROLL_DP_PER_MS = 0.5f

private const val SAME_PX = 0.5f

/**
 * The stop a scroll is heading to, kept from frame to frame. The next stop is chosen from the screen's position, which
 * on the way across the boundary of a spread lies nearer the page just left, so choosing it again each frame would
 * turn back. The leg holds while the screen is still where it left it, going the same way.
 */
data class BreathLeg(val turn: Turn, val target: Point, val rests: Point)

/** One frame of a long sound: the [pan] and the [leg] that the next frame goes on with, `null` at a stop. */
data class BreathScroll(val pan: ReaderEvent.Pan, val leg: BreathLeg?)

/**
 * The pan that moves the screen [distancePx] along the stops of the current view toward [turn]: to the next stop past
 * the screen, then on to the one after it. It stops at the last stop of the view, the first going back, and does not
 * turn. `null` when the screen cannot move, as on a view that fits the screen. [leg] is what the frame before left.
 */
fun ReaderState.breathPan(turn: Turn, distancePx: Float, leg: BreathLeg? = null): BreathScroll? {
    val geometry = geometry() ?: return null
    if (!geometry.overflows) return null
    val path = geometry.stops(config)
    val from = positionOf(geometry, place)
    var at = from
    var left = distancePx
    var heading = leg?.takeIf { it.turn == turn && apart(it.rests, from) <= SAME_PX && apart(it.target, from) > SAME_PX }?.target
    var aimed: Point? = null
    repeat(path.stops.size) {
        if (left <= 0f) return@repeat
        val target = heading ?: path.toward(at, turn) ?: return@repeat
        heading = null
        val gap = apart(target, at)
        if (gap <= left) {
            at = target
            left -= gap
            aimed = null
        } else {
            at = Point(at.x + (target.x - at.x) * left / gap, at.y + (target.y - at.y) * left / gap)
            left = 0f
            aimed = target
        }
    }
    if (at == from) return null
    return BreathScroll(ReaderEvent.Pan(from.x - at.x, from.y - at.y), aimed?.let { BreathLeg(turn, it, at) })
}

private fun apart(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

/** The stop the screen at [position] scrolls to toward [turn], or `null` past the last one that way. */
private fun StopPath.toward(position: Point, turn: Turn): Point? {
    val ahead = nextAfter(position)
    if (turn == Turn.Forward) return ahead?.let { stops[it].point }
    return ((ahead ?: stops.size) - 1 downTo 0).map { stops[it].point }.firstOrNull { apart(it, position) > SAME_PX }
}
