package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.Turn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BreathScrollTest {
    private val config = ReaderConfig(scaleMode = ScaleMode.FitWidth)

    /** One tall page across a landscape screen: its stops run down the page. */
    private val tall = readerOf(List(2) { TALL }, config.copy(layout = config.layout.copy(mode = LayoutMode.Single)), LANDSCAPE)

    private fun ReaderState.scrolled(turn: Turn, px: Float): ReaderState = breathPan(turn, px)?.let(::reduce) ?: this

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

    private fun ReaderState.act(action: ReaderAction) = reduce(ReaderEvent.Act(action, 0)).reduce(ReaderEvent.Tick(Long.MAX_VALUE))
}
