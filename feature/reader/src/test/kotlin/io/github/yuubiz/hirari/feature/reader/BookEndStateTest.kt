package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun ReaderState.act(action: ReaderAction, nowMs: Long = 0) = reduce(ReaderEvent.Act(action, nowMs))

private val NEXT = BookAddress.Document(UriString("content://next"))

class BookEndStateTest {
    private val single = ReaderConfig(layout = LayoutSettings(LayoutMode.Single, ReadingDirection.RightToLeft, coverAlone = false))

    @Test
    fun turningPastTheLastViewRunsIntoTheEnd() {
        val state = readerOf(List(3) { TALL }, single, PORTRAIT, start = 2)
        val after = state.act(ReaderAction.TurnView(Turn.Forward), nowMs = 50)
        assertEquals(EdgeHit(BookEdge.End, 50), after.edgeHit)
        assertEquals(state.current, after.current)
    }

    @Test
    fun turningBackFromTheFirstViewRunsIntoTheStart() {
        val after = readerOf(List(3) { TALL }, single, PORTRAIT).act(ReaderAction.StepStops(Turn.Back), nowMs = 7)
        assertEquals(EdgeHit(BookEdge.Start, 7), after.edgeHit)
    }

    @Test
    fun aTurnThatMovesIsNoEdge() {
        assertNull(readerOf(List(3) { TALL }, single, PORTRAIT).act(ReaderAction.TurnView(Turn.Forward)).edgeHit)
    }

    @Test
    fun aViewWhoseShapesAreUnknownIsNoEdgeYet() {
        val state = ReaderState.open(1, 0, single, PORTRAIT)
        assertNull(state.act(ReaderAction.TurnView(Turn.Forward)).edgeHit)
    }

    @Test
    fun steppingPastTheLastStopOfTheLastViewRunsIntoTheEnd() {
        val zoomed = single.copy(scaleMode = ScaleMode.FitWidth)
        var state = readerOf(listOf(PageShape.Known(1000, 4000)), zoomed, PORTRAIT)
        repeat(10) { state = state.act(ReaderAction.StepStops(Turn.Forward)).reduce(ReaderEvent.Tick(Long.MAX_VALUE)) }
        assertEquals(BookEdge.End, state.act(ReaderAction.StepStops(Turn.Forward), nowMs = 9).edgeHit?.edge)
    }

    @Test
    fun aCommittedPullPastTheEndRunsIntoItAndAReleasedOneDoesNot() {
        val state = readerOf(List(2) { TALL }, single, PORTRAIT, start = 1).reduce(ReaderEvent.Pulling(Turn.Forward, 140f))
        assertEquals(EdgeHit(BookEdge.End, 3), state.reduce(ReaderEvent.PullEnded(commit = true, nowMs = 3)).edgeHit)
        assertNull(state.reduce(ReaderEvent.PullEnded(commit = false, nowMs = 3)).edgeHit)
    }

    @Test
    fun autoPlayStopsAtTheEndWithoutRunningIntoIt() {
        val state = readerOf(List(1) { TALL }, single, PORTRAIT).reduce(ReaderEvent.AutoPlayStarted(0))
        val after = state.reduce(ReaderEvent.AutoPlayDue(Long.MAX_VALUE / 2))
        assertNull(after.autoPlay)
        assertNull(after.edgeHit)
    }

    @Test
    fun theHitEndsWithItsBounce() {
        val hit = readerOf(List(1) { TALL }, single, PORTRAIT).act(ReaderAction.TurnView(Turn.Forward), nowMs = 100)
        assertEquals(hit.edgeHit, hit.reduce(ReaderEvent.Tick(100 + EdgeHit.BOUNCE_MS - 1)).edgeHit)
        assertNull(hit.reduce(ReaderEvent.Tick(100 + EdgeHit.BOUNCE_MS)).edgeHit)
    }

    @Test
    fun theBounceGoesOutTowardTheNextViewAndComesBack() {
        val hit = EdgeHit(BookEdge.End, 0)
        val right = turnMovement(Turn.Forward, ReadingDirection.RightToLeft).toFloat()
        assertEquals(0f, hit.bounceDpAt(0, ReadingDirection.RightToLeft), 0.01f)
        assertEquals(right * EdgeHit.BOUNCE_DP, hit.bounceDpAt(EdgeHit.BOUNCE_MS / 2, ReadingDirection.RightToLeft), 0.01f)
        assertEquals(0f, hit.bounceDpAt(EdgeHit.BOUNCE_MS, ReadingDirection.RightToLeft), 0.01f)
        assertTrue(EdgeHit(BookEdge.Start, 0).bounceDpAt(EdgeHit.BOUNCE_MS / 2, ReadingDirection.RightToLeft) * right < 0)
    }

    @Test
    fun theAtBookEndSettingComesFromTheSettings() {
        val settings = GlobalSettings(atBookEnd = BookEndAction.Close)
        assertEquals(BookEndAction.Close, ReaderConfig.of(settings).atBookEnd)
    }
}

class BookEndEffectTest {
    private val found = Sibling.Found(NEXT)

    @Test
    fun askingShowsTheSheetWithoutLookingTheNeighborUp() = runTest {
        assertEquals(BookEndEffect.Ask(BookEdge.End), bookEndEffect(BookEdge.End, BookEndAction.Ask) { error("looked up") })
    }

    @Test
    fun nextBookOpensTheNextFromItsStartAndThePreviousAtItsEnd() = runTest {
        assertEquals(BookEndEffect.Open(OpenBookRequest(NEXT, StartAt.Page(0))), bookEndEffect(BookEdge.End, BookEndAction.NextBook) { found })
        val previous = bookEndEffect(BookEdge.Start, BookEndAction.NextBook) { found } as BookEndEffect.Open
        assertEquals(StartAt.Page(Int.MAX_VALUE), previous.request.start)
    }

    @Test
    fun noNeighborSaysWhy() = runTest {
        assertEquals(BookEndEffect.Missing(BookEdge.End, Sibling.Last), bookEndEffect(BookEdge.End, BookEndAction.NextBook) { Sibling.Last })
        assertEquals(BookEndEffect.Missing(BookEdge.Start, Sibling.NoFolder), bookEndEffect(BookEdge.Start, BookEndAction.NextBook) { Sibling.NoFolder })
    }

    @Test
    fun closeClosesAtTheEndAndStopAndCloseAtTheStartDoNothingMore() = runTest {
        assertEquals(BookEndEffect.Close, bookEndEffect(BookEdge.End, BookEndAction.Close) { error("looked up") })
        assertNull(bookEndEffect(BookEdge.Start, BookEndAction.Close) { error("looked up") })
        assertNull(bookEndEffect(BookEdge.End, BookEndAction.Stop) { error("looked up") })
    }

    @Test
    fun theNeighborStartsWhereTheChoiceSays() {
        assertEquals(StartAt.Saved, found.request(NeighborStart.Saved).start)
        assertEquals(StartAt.Page(0), found.request(NeighborStart.First).start)
        assertEquals(StartAt.Page(Int.MAX_VALUE), found.request(NeighborStart.Last).start)
    }
}
