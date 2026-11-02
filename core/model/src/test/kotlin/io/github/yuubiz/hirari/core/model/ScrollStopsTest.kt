package io.github.yuubiz.hirari.core.model

import io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight
import io.github.yuubiz.hirari.core.model.ReadingDirection.RightToLeft
import io.github.yuubiz.hirari.core.model.StopOrder.ColumnsFirst
import io.github.yuubiz.hirari.core.model.StopOrder.RowsFirst
import io.kotest.property.Arb
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrollStopsTest {
    private val screen = Size(1000f, 1000f)

    private fun stops(
        width: Float,
        height: Float,
        maxStep: Float,
        order: StopOrder = RowsFirst,
        direction: ReadingDirection = RightToLeft,
    ) = scrollStops(Rect(0f, 0f, width, height), screen, maxStep, order, direction)

    private fun assertPoints(expected: List<Pair<Number, Number>>, actual: List<Point>) {
        assertEquals(expected.size, actual.size, "$actual")
        for ((e, a) in expected.zip(actual)) {
            assertEquals(e.first.toFloat(), a.x, 0.01f, "$actual")
            assertEquals(e.second.toFloat(), a.y, 0.01f, "$actual")
        }
    }

    @Test
    fun contentThatFitsIsCentered() = assertPoints(listOf(-100 to -200), stops(800f, 600f, 0.35f))

    @Test
    fun overflowSplitsIntoEqualSteps() = assertPoints(listOf(0 to 0, 0 to 350, 0 to 700), stops(1000f, 1700f, 0.35f))

    @Test
    fun stepsNeverExceedTheMaximum() {
        val ys = stops(1000f, 3000f, 0.35f).map { it.y }
        assertEquals(7, ys.size)
        assertPoints(ys.mapIndexed { i, _ -> 0 to 2000f * i / 6 }, ys.map { Point(0f, it) })
    }

    @Test
    fun exactMultipleOfAnInexactStepAddsNoStop() =
        assertPoints(listOf(0 to 0, 0 to 700, 0 to 1400), stops(1000f, 2400f, 0.7f))

    @Test
    fun rightToLeftStartsAtTheRightEdge() =
        assertPoints(listOf(1000 to 0, 500 to 0, 0 to 0), stops(2000f, 1000f, 0.5f, direction = RightToLeft))

    @Test
    fun leftToRightStartsAtTheLeftEdge() =
        assertPoints(listOf(0 to 0, 500 to 0, 1000 to 0), stops(2000f, 1000f, 0.5f, direction = LeftToRight))

    @Test
    fun rowsFirstMovesAcrossBeforeDown() = assertPoints(
        listOf(500 to 0, 0 to 0, 500 to 500, 0 to 500),
        stops(1500f, 1500f, 0.5f, RowsFirst),
    )

    @Test
    fun columnsFirstMovesDownBeforeAcross() = assertPoints(
        listOf(500 to 0, 500 to 500, 0 to 0, 0 to 500),
        stops(1500f, 1500f, 0.5f, ColumnsFirst),
    )

    @Test
    fun stopsAreInTheContentCoordinates() = assertPoints(
        listOf(200 to 50, 200 to 550),
        scrollStops(Rect(200f, 50f, 1200f, 1550f), screen, 0.5f, RowsFirst, LeftToRight),
    )

    @Test
    fun stopsCoverTheOverflowInEqualStepsWithinTheMaximum(): Unit = runBlocking {
        checkAll(Arb.float(1000f..20000f, includeNaNs = false), Arb.int(1..20)) { height, percent ->
            val maxStep = percent * 0.05f
            val ys = stops(1000f, height, maxStep, ColumnsFirst).map { it.y }
            val overflow = height - 1000f
            assertEquals(0f, ys.first(), 0.01f)
            assertEquals(if (ys.size == 1) 0f else overflow, ys.last(), overflow * 1e-5f + 0.01f)
            val moves = ys.zipWithNext { a, b -> b - a }
            for (move in moves) {
                assertTrue(move <= 1000f * maxStep * 1.001f, "move $move exceeds ${1000f * maxStep}")
                assertTrue(abs(move - moves.first()) <= overflow * 1e-5f, "moves differ: $moves")
            }
        }
    }
}
