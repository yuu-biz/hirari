package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.Turn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/spec.md section 4.8: a view larger than the screen glides on after a finger lets go of it while moving. */
class GlideTest {
    /** A portrait pair two screens wide, panned 100 px off its first stop, the right page. */
    private val panned = readerOf(List(4) { TALL }, spreadConfig(), PORTRAIT).reduce(ReaderEvent.Pan(100f, 0f))

    /** The states frame by frame from 0 ms until the glide ends, at most ten seconds. */
    private fun ReaderState.frames(): List<ReaderState> {
        val frames = mutableListOf(this)
        var now = 0L
        while (frames.last().place is Place.Gliding && now < 10_000) {
            now += 16
            frames += frames.last().reduce(ReaderEvent.Tick(now))
        }
        return frames
    }

    @Test
    fun aFlungViewGlidesTheWayTheFingerWentSlowsAndRests() {
        val start = panned.positionAt(0)!!
        val frames = panned.reduce(ReaderEvent.Fling(velocityX = 3000f, velocityY = 0f, nowMs = 0)).frames()
        val xs = frames.map { it.positionOf(it.geometry()!!, it.place).x }
        assertTrue(xs.zipWithNext().all { (a, b) -> b <= a }, "a finger moving right shows what lies left: $xs")
        val steps = xs.zipWithNext { a, b -> a - b }
        assertTrue(steps.first() > steps.last(), "the glide slows: $steps")
        val rest = frames.last()
        assertTrue(rest.place is Place.Panned, "it comes to rest off the stops")
        assertNull(rest.stopIndex(), "a glide does not snap to a stop")
        assertTrue(rest.positionAt(0)!!.x in 0f..start.x - 300f, "it carried on past where the finger let go: ${rest.positionAt(0)}")
        assertEquals(PageView.Two(0, 1), rest.current)
    }

    @Test
    fun aGlideStopsAtTheEdgeOfTheViewAndDoesNotTurn() {
        val rest = panned.reduce(ReaderEvent.Fling(velocityX = 24_000f, velocityY = 0f, nowMs = 0)).frames().last()
        assertEquals(0f, rest.positionAt(0)!!.x)
        assertEquals(PageView.Two(0, 1), rest.current)
        assertNull(rest.transition)
    }

    @Test
    fun aTouchStopsAGlideWhereItIs() {
        val gliding = panned.reduce(ReaderEvent.Fling(velocityX = 6000f, velocityY = 0f, nowMs = 0)).reduce(ReaderEvent.Tick(100))
        val at = gliding.positionAt(100)!!
        val caught = gliding.reduce(ReaderEvent.Catch)
        assertEquals(Place.Panned(at), caught.place)
        assertEquals(at, caught.reduce(ReaderEvent.Tick(500)).positionAt(500))
    }

    @Test
    fun aSlowReleaseOrAViewThatFitsDoesNotGlide() {
        assertEquals(panned, panned.reduce(ReaderEvent.Fling(velocityX = 100f, velocityY = 0f, nowMs = 0)))
        val fits = readerOf(List(4) { TALL }, spreadConfig().copy(scaleMode = ScaleMode.FitScreen), PORTRAIT)
        assertEquals(fits, fits.reduce(ReaderEvent.Pan(100f, 0f)).reduce(ReaderEvent.Fling(velocityX = 6000f, velocityY = 0f, nowMs = 0)))
    }

    @Test
    fun theNextStepGoesOnFromWhereTheGlideIs() {
        val gliding = panned.reduce(ReaderEvent.Fling(velocityX = 3000f, velocityY = 0f, nowMs = 0)).reduce(ReaderEvent.Tick(100))
        val stepped = gliding.reduce(ReaderEvent.Act(ReaderAction.StepStops(Turn.Forward), 100))
        assertEquals(1, stepped.stopIndex(), "past the right page the next stop is the left page")
        assertEquals(gliding.positionAt(100), stepped.stopTravel!!.move.from)
        assertEquals(0f, stepped.stopTravel!!.move.to.x)
    }
}
