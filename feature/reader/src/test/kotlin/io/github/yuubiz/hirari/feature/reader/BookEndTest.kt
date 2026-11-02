package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.feature.reader.BookEdge.End
import io.github.yuubiz.hirari.feature.reader.BookEdge.Start
import kotlin.test.Test
import kotlin.test.assertEquals

class BookEndTest {
    @Test
    fun askingShowsTheSheetAtBothEnds() {
        assertEquals(BookEndOutcome.Ask(End), bookEndOutcome(End, BookEndAction.Ask, autoPlaying = false))
        assertEquals(BookEndOutcome.Ask(Start), bookEndOutcome(Start, BookEndAction.Ask, autoPlaying = false))
    }

    @Test
    fun nextBookOpensTheNeighbor() {
        assertEquals(BookEndOutcome.OpenNeighbor(End), bookEndOutcome(End, BookEndAction.NextBook, autoPlaying = false))
        assertEquals(BookEndOutcome.OpenNeighbor(Start), bookEndOutcome(Start, BookEndAction.NextBook, autoPlaying = false))
    }

    @Test
    fun closeClosesAtTheEndAndDoesNothingAtTheStart() {
        assertEquals(BookEndOutcome.CloseReader, bookEndOutcome(End, BookEndAction.Close, autoPlaying = false))
        assertEquals(BookEndOutcome.Ignore, bookEndOutcome(Start, BookEndAction.Close, autoPlaying = false))
    }

    @Test
    fun stopBouncesAtBothEnds() {
        assertEquals(BookEndOutcome.Bounce, bookEndOutcome(End, BookEndAction.Stop, autoPlaying = false))
        assertEquals(BookEndOutcome.Bounce, bookEndOutcome(Start, BookEndAction.Stop, autoPlaying = false))
    }

    @Test
    fun autoPlayOnlyStopsAtTheEnd() {
        for (action in BookEndAction.entries) {
            assertEquals(BookEndOutcome.StopAutoPlay, bookEndOutcome(End, action, autoPlaying = true), "$action")
        }
    }

    @Test
    fun aForwardTurnRunsIntoTheEnd() {
        assertEquals(End, edgeOf(Turn.Forward))
        assertEquals(Start, edgeOf(Turn.Back))
    }
}
