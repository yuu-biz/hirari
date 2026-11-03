package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.KeyCodes
import io.github.yuubiz.hirari.core.model.KeyResult
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReaderRegions
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.TapZones
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.model.VolumeKeys
import io.github.yuubiz.hirari.core.model.pullTurn

/** A part of the turn area where a tap does the same thing everywhere: [turn] is `null` for the zone that does nothing. */
data class GuideZone(val rect: Rect, val turn: Turn?)

/**
 * What the operation guide draws (docs/spec.md section 4.8, 操作ガイド), in the dp of the reader screen. The zones come
 * from asking [TapZones.turnAt], the function a tap is decided by, so they cannot differ from what the reader does.
 */
data class GuideLayout(val commandBar: Rect, val turnArea: Rect, val pageBar: Rect, val zones: List<GuideZone>)

/** What the guide says besides the zones, worked out from the same bindings the reader reads. */
data class GuideContent(
    val layout: GuideLayout,
    /** The side a finger is pulled toward to go forward, or `null` when 「スワイプでめくる」 is off and a drag only scrolls. */
    val swipeForward: Side?,
    /** What the left arrow key does; the right arrow key does the other. */
    val leftKeyTurn: Turn,
    val volumeKeys: VolumeKeys,
)

fun guideContent(regions: ReaderRegions, config: ReaderConfig): GuideContent {
    val turnArea = regions.turnArea
    val bars = regions.barHeight
    val layout = GuideLayout(
        commandBar = Rect(0f, 0f, regions.screen.width, bars),
        turnArea = turnArea,
        pageBar = Rect(0f, regions.screen.height - bars, regions.screen.width, regions.screen.height),
        zones = tapZoneRects(config.tapZones, Size(turnArea.width, turnArea.height)).map {
            GuideZone(Rect(it.rect.left, it.rect.top + turnArea.top, it.rect.right, it.rect.bottom + turnArea.top), it.turn)
        },
    )
    val swipeForward = if (config.swipeToTurn) {
        listOf(Side.Left, Side.Right).first { pullTurn(it, config.direction, config.mirrored) == Turn.Forward }
    } else {
        null
    }
    val leftKey = (config.keys.onKey(KeyCodes.DPAD_LEFT, pressed = true) as KeyResult.Act).action as ReaderAction.TurnView
    return GuideContent(layout, swipeForward, leftKey.turn, config.volumeKeys)
}

/**
 * The zones of a turn area of [area], in its own coordinates, found by asking [zones] what a tap does along the one
 * axis the answer changes on and cutting the axis where the answer changes. The cuts are exact to the float.
 */
fun tapZoneRects(zones: TapZones, area: Size): List<GuideZone> {
    val across = runsAlong(area.width) { zones.turnAt(Point(it, area.height / 2), area) }
    if (across.size > 1) return across.map { GuideZone(Rect(it.start, 0f, it.end, area.height), it.turn) }
    val down = runsAlong(area.height) { zones.turnAt(Point(area.width / 2, it), area) }
    if (down.size > 1) return down.map { GuideZone(Rect(0f, it.start, area.width, it.end), it.turn) }
    return listOf(GuideZone(Rect(0f, 0f, area.width, area.height), across.single().turn))
}

private data class Run(val start: Float, val end: Float, val turn: Turn?)

private const val PROBE_STEP_DP = 1f
private const val EDGE_INSET_DP = 0.001f

private fun runsAlong(length: Float, turnAt: (Float) -> Turn?): List<Run> {
    val runs = ArrayList<Run>()
    var start = 0f
    var turn = turnAt(0f)
    var previous = 0f
    var at = PROBE_STEP_DP
    while (previous < length - EDGE_INSET_DP) {
        val probe = minOf(at, length - EDGE_INSET_DP)
        val found = turnAt(probe)
        if (found != turn) {
            val cut = cutBetween(previous, probe, turnAt, turn)
            runs += Run(start, cut, turn)
            start = cut
            turn = found
        }
        previous = probe
        at += PROBE_STEP_DP
    }
    runs += Run(start, length, turn)
    return runs
}

/** The point in ([from], [to]] where the answer stops being [before]. */
private fun cutBetween(from: Float, to: Float, turnAt: (Float) -> Turn?, before: Turn?): Float {
    var low = from
    var high = to
    while (Math.nextUp(low) < high) {
        val middle = (low + high) / 2
        if (turnAt(middle) == before) low = middle else high = middle
    }
    return high
}
