package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight
import io.github.yuubiz.hirari.core.model.ReadingDirection.RightToLeft
import io.github.yuubiz.hirari.core.model.TurnEffect
import io.github.yuubiz.hirari.feature.reader.Turn.Back
import io.github.yuubiz.hirari.feature.reader.Turn.Forward
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnAnimationTest {
    @Test
    fun aRightToLeftBookBringsTheNextViewInFromTheLeft() {
        assertEquals(1, turnMovement(Forward, RightToLeft))
        assertEquals(-1, turnMovement(Back, RightToLeft))
        assertEquals(-1, turnMovement(Forward, LeftToRight))
        assertEquals(1, turnMovement(Back, LeftToRight))
    }

    @Test
    fun aSlideMovesBothViewsTogether() {
        val slide = TurnAnimation.turn(TurnEffect.Slide, movement = 1, nowMs = 1_000, durationMs = 150)
        assertEquals(TurnFrame(0f, -1f, 1f, false), slide.frameAt(1_000))
        assertEquals(TurnFrame(0.5f, -0.5f, 1f, false), slide.frameAt(1_075))
        assertEquals(TurnFrame(1f, 0f, 1f, false), slide.frameAt(1_150))
        assertFalse(slide.isDoneAt(1_149))
        assertTrue(slide.isDoneAt(1_150))
    }

    @Test
    fun aCoverSlidesTheNextViewOverTheCurrentOne() {
        val cover = TurnAnimation.turn(TurnEffect.Cover, movement = -1, nowMs = 0, durationMs = 200)
        assertEquals(TurnFrame(0f, 1f, 1f, true), cover.frameAt(0))
        assertEquals(TurnFrame(0f, 0.5f, 1f, true), cover.frameAt(100))
        assertEquals(0f, cover.frameAt(200).incomingShift, absoluteTolerance = 0f)
    }

    @Test
    fun aFadeBringsTheNextViewIn() {
        val fade = TurnAnimation.turn(TurnEffect.Fade, movement = 1, nowMs = 0, durationMs = 100)
        assertEquals(0.25f, fade.frameAt(25).incomingAlpha)
        assertEquals(0f, fade.frameAt(25).incomingShift)
    }

    @Test
    fun noEffectSwitchesAtOnce() {
        val none = TurnAnimation.turn(TurnEffect.None, movement = 1, nowMs = 0, durationMs = 150)
        assertEquals(TurnFrame(0f, 0f, 1f, true), none.frameAt(0))
        assertTrue(none.isDoneAt(0))
    }

    @Test
    fun aZeroDurationSwitchesAtOnce() {
        assertTrue(TurnAnimation.turn(TurnEffect.Slide, movement = 1, nowMs = 0, durationMs = 0).isDoneAt(0))
    }

    @Test
    fun theNextInputEndsTheEffectAtOnce() {
        val slide = TurnAnimation.turn(TurnEffect.Slide, movement = 1, nowMs = 0, durationMs = 150)
        val ended = slide.interruptedAt(40)
        assertTrue(ended.isDoneAt(40))
        assertEquals(slide.frameAt(150), ended.frameAt(40))
    }

    @Test
    fun pullingShowsTheNeighborByThePulledAmount() {
        assertEquals(TurnFrame(0.25f, -0.75f, 1f, false), pullFrame(TurnEffect.Slide, 1, 0.25f))
        assertEquals(TurnFrame(0f, -0.75f, 1f, true), pullFrame(TurnEffect.Cover, 1, 0.25f))
        assertEquals(pullFrame(TurnEffect.Slide, 1, 0.25f), pullFrame(TurnEffect.Fade, 1, 0.25f))
    }

    @Test
    fun pullingWithNoEffectShowsOnlyAHint() {
        assertEquals(TurnFrame(0f, 0f, 0f, true, pullHint = 0.25f), pullFrame(TurnEffect.None, 1, 0.25f))
    }

    @Test
    fun aPullThatTurnsRunsTheRestAtTheSpeedOfAWholeTurn() {
        val rest = TurnAnimation.finishPull(TurnEffect.Fade, movement = 1, pulled = 0.4f, nowMs = 0, durationMs = 150)
        assertEquals(pullFrame(TurnEffect.Fade, 1, 0.4f), rest.frameAt(0))
        assertFalse(rest.isDoneAt(89))
        assertTrue(rest.isDoneAt(90))
        assertEquals(TurnFrame(1f, 0f, 1f, false), rest.frameAt(90))
    }

    @Test
    fun aPullThatTurnsWithNoEffectSwitchesAtOnce() {
        val rest = TurnAnimation.finishPull(TurnEffect.None, movement = 1, pulled = 0.4f, nowMs = 0, durationMs = 150)
        assertEquals(TurnFrame(0f, 0f, 1f, true), rest.frameAt(0))
    }

    @Test
    fun aPullThatDoesNotTurnGoesBackInAbout120Ms() {
        val back = TurnAnimation.returnPull(TurnEffect.Cover, movement = 1, pulled = 0.4f, nowMs = 0)
        assertEquals(pullFrame(TurnEffect.Cover, 1, 0.4f), back.frameAt(0))
        assertEquals(pullFrame(TurnEffect.Cover, 1, 0f), back.frameAt(120))
        assertTrue(back.isDoneAt(120))
    }
}
