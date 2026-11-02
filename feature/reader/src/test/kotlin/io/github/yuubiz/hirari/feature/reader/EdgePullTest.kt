package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight
import io.github.yuubiz.hirari.core.model.ReadingDirection.RightToLeft
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.feature.reader.TouchEvent.Cancel
import io.github.yuubiz.hirari.feature.reader.TouchEvent.Down
import io.github.yuubiz.hirari.feature.reader.TouchEvent.Move
import io.github.yuubiz.hirari.feature.reader.TouchEvent.Up
import io.github.yuubiz.hirari.feature.reader.Turn.Back
import io.github.yuubiz.hirari.feature.reader.Turn.Forward
import io.kotest.property.Arb
import io.kotest.property.arbitrary.float
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EdgePullTest {
    private val rtl = TurnAreaTouch(RightToLeft, mirrored = false, swipeToTurn = true, touchSlop = 8f, holdMs = 500)
    private val both = setOf(Side.Left, Side.Right)
    private val origin = Point(200f, 400f)

    private fun TurnAreaTouch.outcome(vararg events: TouchEvent): TouchOutcome? =
        events.fold(TouchStep(TouchState.Idle)) { step, event -> reduce(step.state, event) }.outcome

    private fun TurnAreaTouch.pull(dx: Float, velocityX: Float = 0f, pullable: Set<Side> = both): TouchOutcome? =
        outcome(Down(origin, 0, pullable), Move(Point(200f + dx, 400f)), Up(Point(200f + dx, 400f), 100, velocityX))

    @Test
    fun thePullFollowsTheFingerAtFirstThenResists() {
        assertEquals(50f, resistedPull(50f))
        assertEquals(75f, resistedPull(75f))
        assertTrue(resistedPull(100f) < 100f)
        assertTrue(resistedPull(157f) < PULL_COMMIT_DP)
        assertTrue(resistedPull(158f) >= PULL_COMMIT_DP)
        assertEquals(0f, resistedPull(-20f))
    }

    @Test
    fun thePullGrowsWithTheFingerAndStaysUnderTheCap() = runBlocking<Unit> {
        checkAll(Arb.float(0f..2000f, includeNaNs = false), Arb.float(0f..2000f, includeNaNs = false)) { a, b ->
            val (near, far) = if (a <= b) a to b else b to a
            assertTrue(resistedPull(near) <= resistedPull(far), "$near to $far")
            assertTrue(resistedPull(far) <= PULL_MAX_DP)
        }
    }

    @Test
    fun aViewThatFitsCanBePulledBothWays() {
        assertEquals(both, pullableSides(hiddenLeft = 0f, hiddenRight = 0f))
        assertEquals(setOf(Side.Right), pullableSides(hiddenLeft = 0f, hiddenRight = 300f))
        assertEquals(setOf(Side.Left), pullableSides(hiddenLeft = 300f, hiddenRight = 0f))
        assertEquals(emptySet(), pullableSides(hiddenLeft = 10f, hiddenRight = 10f))
    }

    @Test
    fun pullingRightGoesForwardInARightToLeftBook() {
        assertEquals(Forward, pullTurn(Side.Right, RightToLeft, mirrored = false))
        assertEquals(Back, pullTurn(Side.Left, RightToLeft, mirrored = false))
        assertEquals(Forward, pullTurn(Side.Left, LeftToRight, mirrored = false))
        assertEquals(Back, pullTurn(Side.Right, RightToLeft, mirrored = true))
        assertEquals(Forward, pullTurn(Side.Right, LeftToRight, mirrored = true))
    }

    @Test
    fun aLongPullTurns() {
        val outcome = assertIs<TouchOutcome.Commit>(rtl.pull(200f))
        assertEquals(Forward, outcome.turn)
        assertTrue(outcome.pulled >= PULL_COMMIT_DP)
        assertEquals(Back, assertIs<TouchOutcome.Commit>(rtl.pull(-200f)).turn)
    }

    @Test
    fun aShortPullGoesBack() {
        assertEquals(Forward, assertIs<TouchOutcome.Return>(rtl.pull(100f)).turn)
    }

    @Test
    fun aFastPullOfTwentyFourDpTurns() {
        assertEquals(TouchOutcome.Commit(Forward, 24f), rtl.pull(24f, velocityX = 1000f))
        assertEquals(TouchOutcome.Return(Forward, 24f), rtl.pull(24f, velocityX = 999f))
        assertEquals(TouchOutcome.Return(Forward, 23f), rtl.pull(23f, velocityX = 5000f))
        assertEquals(TouchOutcome.Commit(Back, 30f), rtl.pull(-30f, velocityX = -1000f))
    }

    @Test
    fun aFlingAgainstThePullDoesNotTurn() {
        assertEquals(TouchOutcome.Return(Forward, 30f), rtl.pull(30f, velocityX = -3000f))
    }

    @Test
    fun aZoomedViewScrollsUnlessItWasAtTheEdgeWhenTheFingerWentDown() {
        assertNull(rtl.pull(200f, pullable = setOf(Side.Left)))
        assertIs<TouchOutcome.Commit>(rtl.pull(-200f, pullable = setOf(Side.Left)))
    }

    @Test
    fun turningSwipesOffMakesEveryDragAScroll() {
        assertNull(rtl.copy(swipeToTurn = false).pull(200f))
    }

    @Test
    fun aVerticalDragScrolls() {
        assertNull(rtl.outcome(Down(origin, 0, both), Move(Point(230f, 500f)), Move(Point(400f, 500f)), Up(Point(400f, 500f), 100, 0f)))
    }

    @Test
    fun turningBackPastTheStartLeavesNothingPulled() {
        val outcome = rtl.outcome(Down(origin, 0, both), Move(Point(300f, 400f)), Move(Point(150f, 400f)), Up(Point(150f, 400f), 100, 0f))
        assertEquals(TouchOutcome.Return(Forward, 0f), outcome)
    }

    @Test
    fun aShortStillPressIsATap() {
        assertEquals(TouchOutcome.Tap(origin), rtl.outcome(Down(origin, 0, both), Up(Point(205f, 403f), 499, 0f)))
        assertNull(rtl.outcome(Down(origin, 0, both), Up(origin, 500, 0f)))
    }

    @Test
    fun aDragIsNotATap() {
        assertNull(rtl.outcome(Down(origin, 0, both), Move(Point(200f, 450f)), Up(Point(200f, 400f), 100, 0f)))
    }

    @Test
    fun cancellingAPullGoesBack() {
        assertEquals(TouchOutcome.Return(Forward, 50f), rtl.outcome(Down(origin, 0, both), Move(Point(250f, 400f)), Cancel))
    }
}
