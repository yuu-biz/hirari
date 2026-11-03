package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.Turn
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BreathScrollTest {
    private val config = ReaderConfig(scaleMode = ScaleMode.FitWidth)

    /** One tall page across a landscape screen: its stops run down the page. */
    private val tall = readerOf(List(2) { TALL }, config.copy(layout = config.layout.copy(mode = LayoutMode.Single)), LANDSCAPE)

    private fun ReaderState.scrolled(turn: Turn, px: Float): ReaderState = breathPan(turn, px)?.let { reduce(it.pan) } ?: this

    private fun ReaderState.position(): Point = positionAt(0)!!

    @Test
    fun aLongSoundMovesTheScreenDownTheStopsByTheDistance() {
        val start = tall.position()
        val moved = tall.scrolled(Turn.Forward, 30f)
        assertEquals(Point(start.x, start.y + 30f), moved.position())
        assertEquals(PageView.One(0), moved.current)
    }

    @Test
    fun itStopsAtTheLastStopWithoutTurning() {
        val stops = tall.geometry()!!.stops(tall.config).stops
        val end = tall.scrolled(Turn.Forward, 1_000_000f)
        assertEquals(stops.last().point, end.position())
        assertEquals(PageView.One(0), end.current)
        assertNull(end.breathPan(Turn.Forward, 10f))
    }

    @Test
    fun goingBackMovesUpToTheFirstStop() {
        val stops = tall.geometry()!!.stops(tall.config).stops
        assertTrue(stops.size > 2)
        val atEnd = tall.act(ReaderAction.StepStops(Turn.Forward)).act(ReaderAction.StepStops(Turn.Forward))
        val back = atEnd.scrolled(Turn.Back, 10f)
        assertEquals(Point(stops[2].point.x, stops[2].point.y - 10f), back.position())
        assertEquals(stops[0].point, back.scrolled(Turn.Back, 1_000_000f).position())
        assertNull(tall.breathPan(Turn.Back, 10f))
    }

    @Test
    fun aScrollPassesThroughAStopAndGoesOn() {
        val stops = tall.geometry()!!.stops(tall.config).stops
        val gap = stops[1].point.y - stops[0].point.y
        assertEquals(Point(stops[1].point.x, stops[1].point.y + 5f), tall.scrolled(Turn.Forward, gap + 5f).position())
    }

    @Test
    fun aViewThatFitsTheScreenDoesNotScroll() {
        assertNull(readerOf(List(2) { TALL }).breathPan(Turn.Forward, 50f))
    }

    /** A spread wider and taller than the screen: its stops run down the right page and on to the left page. */
    private val spread = readerOf(List(2) { TALL }, spreadConfig().copy(scaleMode = ScaleMode.Original), LANDSCAPE)

    private fun walk(start: ReaderState, turn: Turn, frameDistance: Float, frames: Int): List<ReaderState> {
        val states = mutableListOf(start)
        var leg: BreathLeg? = null
        repeat(frames) {
            val scroll = states.last().breathPan(turn, frameDistance, leg) ?: return states
            leg = scroll.leg
            states += states.last().reduce(scroll.pan)
        }
        return states
    }

    @Test
    fun aScrollCrossesTheBoundaryOfASpreadWithoutGoingBack() {
        val stops = spread.geometry()!!.stops(spread.config).stops
        assertTrue(stops.map { it.page }.toSet().size == 2)
        val path = stops.zipWithNext().sumOf { (a, b) -> hypot((b.point.x - a.point.x).toDouble(), (b.point.y - a.point.y).toDouble()) }
        val walked = walk(spread, Turn.Forward, 8f, (path / 8).toInt() + stops.size + 2)
        assertEquals(stops.last().point, walked.last().position())
        assertEquals(walked.map { it.position() }.distinct().size, walked.size)
    }

    @Test
    fun aScrollBackCrossesTheBoundaryOfASpreadWithoutGoingForward() {
        val stops = spread.geometry()!!.stops(spread.config).stops
        val path = stops.zipWithNext().sumOf { (a, b) -> hypot((b.point.x - a.point.x).toDouble(), (b.point.y - a.point.y).toDouble()) }
        val atEnd = walk(spread, Turn.Forward, 1_000_000f, 1).last()
        val walked = walk(atEnd, Turn.Back, 8f, (path / 8).toInt() + stops.size + 2)
        assertEquals(stops.first().point, walked.last().position())
        assertEquals(walked.map { it.position() }.distinct().size, walked.size)
    }

    @Test
    fun aLegIsDroppedWhenTheScreenWasMovedByHandOrTurnsBack() {
        val first = tall.breathPan(Turn.Forward, 30f)!!
        val moved = tall.reduce(first.pan)
        val byHand = moved.reduce(ReaderEvent.Pan(0f, -50f))
        val stops = tall.geometry()!!.stops(tall.config).stops
        val fresh = byHand.breathPan(Turn.Forward, 10f, first.leg)!!
        assertEquals(byHand.breathPan(Turn.Forward, 10f)!!.pan, fresh.pan)
        assertEquals(stops[0].point, moved.breathPan(Turn.Back, 1_000_000f, first.leg)!!.let { moved.reduce(it.pan) }.position())
    }

    @Test
    fun aLongSoundScrollsTheVerticalStripUpToItsEnd() {
        val vertical = config.copy(layout = LayoutSettings(LayoutMode.Vertical, ReadingDirection.RightToLeft, coverAlone = true))
        val strip = readerOf(List(3) { PageShape.Known(800, 1600) }, vertical, PORTRAIT)
        val top = { state: ReaderState -> state.scroll!!.top(state.strip, state.stripScreen) }
        val scrolled = strip.scrolled(Turn.Forward, 300f)
        assertEquals(300.0, top(scrolled), 0.01)
        assertNull(strip.breathPan(Turn.Forward, 300f)!!.leg)
        assertEquals(100.0, top(scrolled.scrolled(Turn.Back, 200f)), 0.01)
        val end = strip.scrolled(Turn.Forward, 1e7f)
        assertTrue(end.scroll!!.endInView(end.strip, end.stripScreen))
        assertNull(end.breathPan(Turn.Forward, 10f))
        assertNull(strip.breathPan(Turn.Back, 10f))
    }

    private fun ReaderState.act(action: ReaderAction) = reduce(ReaderEvent.Act(action, 0)).reduce(ReaderEvent.Tick(Long.MAX_VALUE))
}
