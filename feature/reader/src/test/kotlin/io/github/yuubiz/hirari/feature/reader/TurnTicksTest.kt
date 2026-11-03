package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Turn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnTicksTest {
    private val ticking = spreadConfig().copy(turnHaptics = true)
    private val book = readerOf(List(6) { TALL }, ticking)

    private fun ticks(state: ReaderState, event: ReaderEvent) = turnTicks(event, state, state.reduce(event))

    @Test
    fun `every way to turn to another view ticks`() {
        assertTrue(ticks(book, ReaderEvent.Act(ReaderAction.TurnView(Turn.Forward), 0)))
        assertTrue(ticks(book, ReaderEvent.Act(ReaderAction.StepStops(Turn.Forward), 0)))
        assertTrue(ticks(book, ReaderEvent.Act(ReaderAction.Last, 0)))
        assertTrue(ticks(book, ReaderEvent.GoTo(4)))
        val pulling = book.reduce(ReaderEvent.Pulling(Turn.Forward, 400f))
        assertTrue(ticks(pulling, ReaderEvent.PullEnded(commit = true, nowMs = 0)))
        val playing = book.reduce(ReaderEvent.AutoPlayStarted(nowMs = 0))
        assertTrue(ticks(playing, ReaderEvent.AutoPlayDue(nowMs = playing.autoPlay!!.dueAtMs)))
    }

    @Test
    fun `a turn that does not change the view stays quiet`() {
        val last = book.reduce(ReaderEvent.GoTo(5))
        assertFalse(ticks(last, ReaderEvent.Act(ReaderAction.TurnView(Turn.Forward), 0)), "past the end")
        assertFalse(ticks(book, ReaderEvent.Act(ReaderAction.TurnView(Turn.Back), 0)), "before the start")
        assertFalse(ticks(book, ReaderEvent.Act(ReaderAction.OpenMenu, 0)))
        val pulling = book.reduce(ReaderEvent.Pulling(Turn.Forward, 30f))
        assertFalse(ticks(pulling, ReaderEvent.PullEnded(commit = false, nowMs = 0)), "a pull that went back")
    }

    @Test
    fun `the setting off keeps the turns quiet`() {
        val quiet = readerOf(List(6) { TALL }, spreadConfig())
        assertFalse(ticks(quiet, ReaderEvent.Act(ReaderAction.TurnView(Turn.Forward), 0)))
        assertFalse(ticks(quiet, ReaderEvent.GoTo(4)))
    }

    @Test
    fun `a view that changes because the layout changed under the reader stays quiet`() {
        val single = ticking.copy(layout = LayoutSettings(LayoutMode.Single, ReadingDirection.RightToLeft, coverAlone = false))
        val relaid = book.reduce(ReaderEvent.ConfigChanged(single))
        assertTrue(book.current != relaid.current || book.pagination.views != relaid.pagination.views, "the layout really changed")
        assertFalse(turnTicks(ReaderEvent.ConfigChanged(single), book, relaid))
        assertEquals(false, turnTicks(ReaderEvent.ViewportChanged(PORTRAIT), book, book.reduce(ReaderEvent.ViewportChanged(PORTRAIT))))
    }
}
