package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.HalfPart
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ReadingPosition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val BOOK = BookId("book")
private val NOW = Instant.parse("2026-11-02T00:00:00Z")
private val SPLIT = ReaderConfig(layout = LayoutSettings(LayoutMode.SplitWide, ReadingDirection.RightToLeft, coverAlone = false))
private val SINGLE = ReaderConfig(layout = LayoutSettings(LayoutMode.Single, ReadingDirection.RightToLeft, coverAlone = false))

private fun stored(state: ReadState, viewStart: Int, pages: Int) =
    BookProgress(BOOK, state, ReadingPosition.Image(viewStart, pages), Instant.EPOCH)

private fun ReaderState.turned(turn: Turn = Turn.Forward) =
    reduce(ReaderEvent.Act(ReaderAction.TurnView(turn), 0)).reduce(ReaderEvent.Tick(Long.MAX_VALUE))

class ProgressLogTest {
    @Test
    fun aBookOpenedForTheFirstTimeBecomesReading() {
        val log = ProgressLog(BOOK, pageCount = 3, opened = null)
        val saved = log.next(ReaderState.open(3, 0, SINGLE, PORTRAIT), NOW)
        assertEquals(BookProgress(BOOK, ReadState.Reading, ReadingPosition.Image(0, 3), NOW), saved)
    }

    @Test
    fun openingAtTheStoredPositionWritesNothingUntilTheViewChanges() {
        val log = ProgressLog(BOOK, pageCount = 5, opened = stored(ReadState.Reading, 2, 5))
        val opened = ReaderState.open(5, 2, SINGLE, PORTRAIT)
        assertNull(log.next(opened, NOW))
        assertEquals(ReadingPosition.Image(3, 5), log.next(opened.turned(), NOW)?.position)
    }

    @Test
    fun theSameViewIsNotWrittenTwice() {
        val log = ProgressLog(BOOK, pageCount = 5, opened = null)
        val state = ReaderState.open(5, 0, SINGLE, PORTRAIT)
        log.next(state, NOW)
        assertNull(log.next(state.reduce(ReaderEvent.ShapeLearned(0, TALL)), NOW))
    }

    @Test
    fun aWidePageAloneIsReadOnlyAtItsSecondHalf() {
        val log = ProgressLog(BOOK, pageCount = 1, opened = null)
        val provisional = ReaderState.open(1, 0, SPLIT, PORTRAIT)
        assertEquals(ReadState.Reading, log.next(provisional, NOW)?.state)
        val first = provisional.reduce(ReaderEvent.ShapeLearned(0, WIDE))
        assertEquals(PageView.Half(0, HalfPart.First), first.current)
        assertNull(log.next(first, NOW), "the first half is the same position, still Reading")
        assertEquals(ReadState.Read, log.next(first.turned(), NOW)?.state)
    }

    @Test
    fun aTallPageAloneIsReadOnceItsShapeIsKnown() {
        val log = ProgressLog(BOOK, pageCount = 1, opened = null)
        val provisional = ReaderState.open(1, 0, SPLIT, PORTRAIT)
        assertEquals(ReadState.Reading, log.next(provisional, NOW)?.state)
        assertEquals(ReadState.Read, log.next(provisional.reduce(ReaderEvent.ShapeLearned(0, TALL)), NOW)?.state)
    }

    @Test
    fun aReadBookStaysReadWhenOpenedAgain() {
        val log = ProgressLog(BOOK, pageCount = 3, opened = stored(ReadState.Read, 2, 3))
        val opened = readerOf(List(3) { TALL }, SINGLE, PORTRAIT, start = 2)
        assertEquals(ReadState.Read, log.next(opened.turned(Turn.Back), NOW)?.state)
    }

    @Test
    fun restoringGivesBackWhatTheBookHadWhenItOpened() {
        val before = stored(ReadState.Reading, 1, 3)
        val log = ProgressLog(BOOK, pageCount = 3, opened = before)
        log.next(readerOf(List(3) { TALL }, SINGLE, PORTRAIT, start = 2), NOW)
        assertEquals(before, log.restored(NOW))
    }

    @Test
    fun restoringABookNeverOpenedBeforeMakesItUnreadAgain() {
        val log = ProgressLog(BOOK, pageCount = 3, opened = null)
        log.next(ReaderState.open(3, 0, SINGLE, PORTRAIT), NOW)
        assertEquals(BookProgress(BOOK, ReadState.Unread, null, NOW), log.restored(NOW))
    }
}
