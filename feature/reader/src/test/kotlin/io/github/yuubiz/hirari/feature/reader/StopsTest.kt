package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight
import io.github.yuubiz.hirari.core.model.ReadingDirection.RightToLeft
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.StopOrder
import io.github.yuubiz.hirari.core.model.StopOrder.ColumnsFirst
import io.github.yuubiz.hirari.core.model.StopOrder.RowsFirst
import io.github.yuubiz.hirari.core.model.Turn.Back
import io.github.yuubiz.hirari.core.model.Turn.Forward
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class StopsTest {
    private val screen = Size(1000f, 1000f)

    private fun path(vararg pages: Rect, order: StopOrder = RowsFirst, direction: ReadingDirection = RightToLeft) =
        StopPath.of(pages.toList(), screen, maxStep = 0.5f, order, direction)

    private fun StopPath.points() = stops.map { it.point }

    @Test
    fun aTallPageIsReadDownTheMiddle() {
        assertEquals(listOf(Point(0f, 0f), Point(0f, 500f), Point(0f, 1000f)), path(Rect(0f, 0f, 1000f, 2000f)).points())
    }

    @Test
    fun aWidePageIsReadHalfByHalfFromTheRightHalfInARightToLeftBook() {
        assertEquals(listOf(Stop(Point(1000f, 0f), 0), Stop(Point(0f, 0f), 1)), path(Rect(0f, 0f, 2000f, 1000f)).stops)
        assertEquals(listOf(Stop(Point(0f, 0f), 0), Stop(Point(1000f, 0f), 1)), path(Rect(0f, 0f, 2000f, 1000f), direction = LeftToRight).stops)
    }

    @Test
    fun aWidePageReadsOutItsFirstHalfAndCrossesTheMiddleInOneMove() {
        assertEquals(listOf(Point(2000f, 0f), Point(1500f, 0f), Point(500f, 0f), Point(0f, 0f)), path(Rect(0f, 0f, 3000f, 1000f)).points())
    }

    @Test
    fun aWidePageThatFitsAcrossIsReadAsAWhole() {
        assertEquals(listOf(Point(-100f, -200f)), path(Rect(0f, 0f, 800f, 600f)).points())
    }

    @Test
    fun aWidePairIsReadEarlierPageFirst() {
        val pair = path(Rect(1000f, 0f, 2000f, 1000f), Rect(0f, 0f, 1000f, 1000f))
        assertEquals(listOf(Stop(Point(1000f, 0f), 0), Stop(Point(0f, 0f), 1)), pair.stops)
    }

    @Test
    fun aPairThatFitsAcrossIsReadAsAWhole() {
        val pair = path(Rect(500f, 0f, 1000f, 1400f), Rect(0f, 0f, 500f, 1400f))
        assertEquals(listOf(Point(0f, 0f), Point(0f, 400f)), pair.points())
    }

    @Test
    fun stopsKeepTheScreenInsideTheView() {
        val pair = path(Rect(700f, 0f, 1400f, 1000f), Rect(0f, 0f, 700f, 1000f))
        assertEquals(listOf(Point(400f, 0f), Point(0f, 0f)), pair.points())
    }

    @Test
    fun aViewIsEnteredAtItsFirstStopForwardAndItsLastStopBack() {
        val tall = path(Rect(0f, 0f, 1000f, 2000f))
        assertEquals(0, StopCursor.entering(tall, Forward).index)
        assertEquals(2, StopCursor.entering(tall, Back).index)
    }

    @Test
    fun steppingPastTheEndsLeavesTheView() {
        val tall = path(Rect(0f, 0f, 1000f, 2000f))
        assertEquals(StopStep.Move(StopCursor(tall, 1)), StopCursor(tall, 0).step(Forward))
        assertEquals(StopStep.Leave(Forward), StopCursor(tall, 2).step(Forward))
        assertEquals(StopStep.Leave(Back), StopCursor(tall, 0).step(Back))
    }

    @Test
    fun forwardThenBackComesBackToTheSameStop() = runBlocking<Unit> {
        checkAll(Arb.int(1000..4000), Arb.int(1000..4000), Arb.enum<StopOrder>(), Arb.enum<ReadingDirection>(), Arb.int(0..50)) { w, h, order, direction, at ->
            val stops = path(Rect(0f, 0f, w.toFloat(), h.toFloat()), order = order, direction = direction)
            val cursor = StopCursor(stops, at.coerceAtMost(stops.last))
            when (val forward = cursor.step(Forward)) {
                is StopStep.Move -> assertEquals(StopStep.Move(cursor), forward.cursor.step(Back))
                is StopStep.Leave -> assertEquals(stops.last, cursor.index)
            }
        }
    }

    @Test
    fun afterAPanForwardGoesToTheNearestStopPastTheScreen() {
        val page = path(Rect(0f, 0f, 2000f, 2000f))
        val cursor = StopCursor(page, 0)
        assertEquals(Point(1000f, 0f), page.stops[0].point)
        assertEquals(StopStep.Move(StopCursor(page, 1)), cursor.forwardFrom(Point(700f, 0f)))
        assertEquals(StopStep.Move(StopCursor(page, 2)), cursor.forwardFrom(Point(500f, 0f)))
        assertEquals(StopStep.Move(StopCursor(page, 3)), cursor.forwardFrom(Point(700f, 250f)))
        assertEquals(StopStep.Leave(Forward), cursor.forwardFrom(Point(0f, 1000f)))
    }

    @Test
    fun afterAPanColumnsFirstGoesDownBeforeAcross() {
        val page = path(Rect(0f, 0f, 2000f, 2000f), order = ColumnsFirst)
        val cursor = StopCursor(page, 0)
        assertEquals(StopStep.Move(StopCursor(page, 1)), cursor.forwardFrom(Point(1000f, 200f)))
        assertEquals(StopStep.Move(StopCursor(page, 3)), cursor.forwardFrom(Point(700f, 200f)))
    }

    @Test
    fun afterAPanOnAPairTheRestOfTheEarlierPageComesFirst() {
        val pair = path(Rect(1000f, 0f, 2000f, 1000f), Rect(0f, 0f, 1000f, 1000f))
        assertEquals(StopStep.Move(StopCursor(pair, 1)), StopCursor(pair, 0).forwardFrom(Point(600f, 0f)))
    }

    @Test
    fun stopsMoveAtSeventeenHundredFiftyDpPerSecond() {
        val move = StopMove.between(Point(0f, 0f), Point(0f, 700f), nowMs = 0, pxPerDp = 2f)
        assertEquals(200L, move.motion.durationMs)
        assertEquals(Point(0f, 350f), move.positionAt(100))
        assertEquals(Point(0f, 700f), move.positionAt(200))
    }

    @Test
    fun theNextInputPutsTheScreenOnTheStopAtOnce() {
        val move = StopMove.between(Point(0f, 0f), Point(0f, 700f), nowMs = 0, pxPerDp = 2f)
        assertEquals(Point(0f, 700f), move.interruptedAt(50).positionAt(50))
    }
}
