package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight
import io.github.yuubiz.hirari.core.model.ReadingDirection.RightToLeft
import io.github.yuubiz.hirari.feature.reader.PageBarEvent.Down
import io.github.yuubiz.hirari.feature.reader.PageBarEvent.Hold
import io.github.yuubiz.hirari.feature.reader.PageBarEvent.Move
import io.github.yuubiz.hirari.feature.reader.PageBarEvent.Up
import io.kotest.property.Arb
import io.kotest.property.arbitrary.float
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageBarTest {
    private val rtl = PageBar(left = 0f, right = 400f, top = 740f, height = 60f, pageCount = 200, direction = RightToLeft, touchSlop = 8f)
    private val ltr = rtl.copy(direction = LeftToRight)

    private fun PageBar.run(vararg events: PageBarEvent): List<PageBarStep> =
        events.runningFold(PageBarStep(PageBarState.Idle)) { step, event -> reduce(step.state, event) }.drop(1)

    private fun PageBar.previewAfter(vararg events: PageBarEvent): Int =
        assertIs<PageBarState.Dragging>(run(*events).last().state).preview

    private fun at(x: Float, y: Float = 770f) = Point(x, y)

    @Test
    fun theStaircaseFollowsTheFiveDpUnit() {
        assertEquals(0, pageBarSteps(0f))
        assertEquals(0, pageBarSteps(9.9f))
        assertEquals(1, pageBarSteps(10f))
        assertEquals(1, pageBarSteps(29.9f))
        assertEquals(2, pageBarSteps(30f))
        assertEquals(2, pageBarSteps(44.9f))
        assertEquals(3, pageBarSteps(45f))
        assertEquals(4, pageBarSteps(50f))
        assertEquals(14, pageBarSteps(100f))
        assertEquals(-3, pageBarSteps(-45f))
    }

    @Test
    fun theStaircaseNeverGoesBackAsTheDistanceGrows() = runBlocking<Unit> {
        checkAll(Arb.float(0f..1000f, includeNaNs = false), Arb.float(0f..1000f, includeNaNs = false)) { a, b ->
            val (near, far) = if (a <= b) a to b else b to a
            assertTrue(pageBarSteps(near) <= pageBarSteps(far), "$near to $far")
            assertEquals(-pageBarSteps(far), pageBarSteps(-far))
        }
    }

    @Test
    fun slidingLeftGoesForwardInARightToLeftBook() {
        assertEquals(12 + 3, rtl.previewAfter(Down(at(200f), 0, page = 12), Move(at(155f))))
        assertEquals(12 - 2, rtl.previewAfter(Down(at(200f), 0, page = 12), Move(at(230f))))
    }

    @Test
    fun slidingRightGoesForwardInALeftToRightBook() {
        assertEquals(12 + 1, ltr.previewAfter(Down(at(200f), 0, page = 12), Move(at(210f))))
        assertEquals(12 - 1, ltr.previewAfter(Down(at(200f), 0, page = 12), Move(at(190f))))
    }

    @Test
    fun thePreviewStaysInsideTheBook() {
        assertEquals(0, rtl.previewAfter(Down(at(200f), 0, page = 1), Move(at(300f))))
        assertEquals(199, rtl.previewAfter(Down(at(200f), 0, page = 198), Move(at(100f))))
    }

    @Test
    fun theEdgesSelectTheFirstAndLastPages() {
        assertEquals(199, rtl.previewAfter(Down(at(200f), 0, page = 12), Move(at(24f))))
        assertEquals(0, rtl.previewAfter(Down(at(200f), 0, page = 12), Move(at(376f))))
        assertEquals(0, ltr.previewAfter(Down(at(200f), 0, page = 12), Move(at(24f))))
        assertEquals(199, ltr.previewAfter(Down(at(200f), 0, page = 12), Move(at(376f))))
    }

    @Test
    fun aPressAtAnEdgeSelectsItOnlyAfterLeavingAndComingBack() {
        val down = Down(at(10f), 0, page = 12)
        assertEquals(12 + 1, rtl.previewAfter(down, Move(at(0f))))
        assertEquals(199, rtl.previewAfter(down, Move(at(30f)), Move(at(20f))))
    }

    @Test
    fun releasingInsideTheBarGoesToThePreviewedPage() {
        val steps = rtl.run(Down(at(200f), 0, page = 12), Move(at(155f)), Up(at(155f, 700f)))
        assertEquals(PageBarEffect.GoTo(15), steps.last().effect)
        assertEquals(PageBarState.Idle, steps.last().state)
    }

    @Test
    fun liftingABarHeightAboveTheBarCancels() {
        val steps = rtl.run(Down(at(200f), 0, page = 12), Move(at(155f)), Move(at(155f, 680f)))
        assertTrue(assertIs<PageBarState.Dragging>(steps.last().state).cancelled)
        assertNull(rtl.run(Down(at(200f), 0, page = 12), Move(at(155f)), Up(at(155f, 680f))).last().effect)
    }

    @Test
    fun comingBackBelowTheLineUndoesTheCancel() {
        val steps = rtl.run(Down(at(200f), 0, page = 12), Move(at(155f, 680f)), Up(at(155f, 681f)))
        assertEquals(PageBarEffect.GoTo(15), steps.last().effect)
    }

    @Test
    fun returningToTheStartPageMovesNowhere() {
        assertNull(rtl.run(Down(at(200f), 0, page = 12), Move(at(155f)), Up(at(198f))).last().effect)
    }

    @Test
    fun aTapOpensThePagePanel() {
        assertEquals(PageBarEffect.OpenPagePanel, rtl.run(Down(at(200f), 0, page = 12), Up(at(205f))).last().effect)
    }

    @Test
    fun holdingStillForFourHundredMsOpensThePagePanel() {
        val down = Down(at(200f), 1_000, page = 12)
        assertNull(rtl.run(down, Hold(1_399)).last().effect)
        val held = rtl.run(down, Hold(1_400), Up(at(200f)))
        assertEquals(PageBarEffect.OpenPagePanel, held[1].effect)
        assertNull(held[2].effect)
    }

    @Test
    fun holdingAfterSlidingDoesNothing() {
        assertNull(rtl.run(Down(at(200f), 0, page = 12), Move(at(150f)), Hold(400)).last().effect)
    }
}
