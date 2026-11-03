package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.EdgeHit
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.model.TurnAnimation
import io.github.yuubiz.hirari.core.model.TurnEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TextMotionTest {
    /** Ten pages of ten characters each. */
    private val run = PageRun(IntArray(10) { it * 10 }, end = 100, fromStart = true, complete = true, sourceLength = 100)
    private val width = 400f

    private fun layers(motion: TextMotion?, current: Int = 4, perView: Int = 1, effect: TurnEffect = TurnEffect.Cover, nowMs: Long = 0) =
        textLayers(motion, current, perView, run, effect, width, density = 2f, nowMs)

    @Test
    fun `at rest only the current view is drawn`() {
        assertEquals(listOf(TextLayer(4, 0f, 1f)), layers(null))
    }

    @Test
    fun `turning forward covers the old view with the new one coming in from the left`() {
        val turning = TextMotion.Turning(from = 30, TurnAnimation.turn(TurnEffect.Cover, movement = 1, nowMs = 0, durationMs = 100))
        assertEquals(listOf(TextLayer(3, 0f, 1f), TextLayer(4, -width, 1f)), layers(turning, nowMs = 0))
        assertEquals(listOf(TextLayer(3, 0f, 1f), TextLayer(4, -width / 2, 1f)), layers(turning, nowMs = 50))
        assertEquals(true, turning.isDoneAt(100))
    }

    @Test
    fun `a pull shows the neighbor by the share pulled, and nothing past the ends`() {
        val pull = TextMotion.Pulling(Turn.Forward, pulledDp = 50f)
        assertEquals(listOf(TextLayer(5, -width * 0.75f, 1f), TextLayer(4, width * 0.25f, 1f)), layers(pull, effect = TurnEffect.Slide))
        assertEquals(listOf(TextLayer(9, 0f, 1f)), layers(pull, current = 9))
        assertEquals(listOf(TextLayer(0, 0f, 1f)), layers(TextMotion.Pulling(Turn.Back, 50f), current = 0))
        assertEquals(6, layers(pull, current = 4, perView = 2).last().head)
    }

    @Test
    fun `the bounce of 止まる moves the view and comes back`() {
        val bounce = TextMotion.Bouncing(EdgeHit(BookEdge.End, 0))
        assertEquals(0f, layers(bounce).single().shift)
        assertEquals(EdgeHit.BOUNCE_DP * 2, layers(bounce, nowMs = EdgeHit.BOUNCE_MS / 2).single().shift, 0.01f)
    }

    @Test
    fun `the 連続 row bounces at an end by the same shift, and moves under nothing else`() {
        val bounce = TextMotion.Bouncing(EdgeHit(BookEdge.End, 0))
        assertEquals(0f, bounceShift(bounce, density = 2f, nowMs = 0), 0.01f)
        assertEquals(EdgeHit.BOUNCE_DP * 2, bounceShift(bounce, density = 2f, nowMs = EdgeHit.BOUNCE_MS / 2), 0.01f)
        assertEquals(layers(bounce, nowMs = EdgeHit.BOUNCE_MS / 2).single().shift, bounceShift(bounce, density = 2f, nowMs = EdgeHit.BOUNCE_MS / 2))
        assertEquals(0f, bounceShift(null, density = 2f, nowMs = EdgeHit.BOUNCE_MS / 2))
        assertEquals(0f, bounceShift(TextMotion.Pulling(Turn.Forward, 50f), density = 2f, nowMs = EdgeHit.BOUNCE_MS / 2))
    }

    private val settings = GlobalSettings(turnEffect = TurnEffect.Slide, turnDurationMs = 150)

    @Test
    fun `a turn that moved runs the effect from the view it left, and the 連続 row has none`() {
        val turned = assertIs<TextMotion.Turning>(motionAfter(TurnResult.Moved(30), Turn.Forward, null, settings, continuous = false, nowMs = 5))
        assertEquals(30, turned.from)
        assertEquals(TurnAnimation.turn(TurnEffect.Slide, 1, 5, 150), turned.animation)
        val pulled = assertIs<TextMotion.Turning>(motionAfter(TurnResult.Moved(30), Turn.Forward, 0.5f, settings, continuous = false, nowMs = 5))
        assertEquals(TurnAnimation.finishPull(TurnEffect.Slide, 1, 0.5f, 5, 150), pulled.animation)
        assertNull(motionAfter(TurnResult.Moved(30), Turn.Forward, null, settings, continuous = true, nowMs = 5))
        assertNull(motionAfter(TurnResult.Waiting, Turn.Forward, null, settings, continuous = false, nowMs = 5))
    }

    @Test
    fun `at an end 止まる bounces, 閉じる closes at the end, and the choices that need the next text do nothing`() {
        val stop = settings.copy(atBookEnd = BookEndAction.Stop)
        assertEquals(TextMotion.Bouncing(EdgeHit(BookEdge.End, 5)), motionAfter(TurnResult.Edge(BookEdge.End), Turn.Forward, null, stop, false, 5))
        assertNull(motionAfter(TurnResult.Edge(BookEdge.End), Turn.Forward, null, settings.copy(atBookEnd = BookEndAction.Ask), false, 5))
    }
}
