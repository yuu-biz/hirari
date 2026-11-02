package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.HalfPart
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight
import io.github.yuubiz.hirari.core.model.ReadingDirection.RightToLeft
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.feature.reader.ZoomEvent.FingersDown
import io.github.yuubiz.hirari.feature.reader.ZoomEvent.FingersUp
import io.github.yuubiz.hirari.feature.reader.ZoomEvent.ModeChanged
import io.github.yuubiz.hirari.feature.reader.ZoomEvent.SecondFingerTap
import io.github.yuubiz.hirari.feature.reader.ZoomEvent.SpanChanged
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

class ZoomTest {
    private val shapes = mapOf(0 to PageShape.Known(700, 1000), 1 to PageShape.Known(600, 800), 2 to PageShape.Known(1400, 1000))
    private val fitHalf = ZoomLimits(mode = 0.5f, fit = 0.5f, capAtOriginal = false)

    private fun ZoomState.after(limits: ZoomLimits, vararg events: ZoomEvent) = events.fold(this) { state, event -> state.reduce(event, limits) }

    @Test
    fun aSinglePageKeepsItsSize() {
        assertEquals(Size(700f, 1000f), viewContent(PageView.One(0), shapes::getValue, RightToLeft).size)
    }

    @Test
    fun aHalfIsHalfTheWidePage() {
        assertEquals(Size(700f, 1000f), viewContent(PageView.Half(2, HalfPart.First), shapes::getValue, RightToLeft).size)
    }

    @Test
    fun aPairGrowsItsLowerPageToTheTallerOne() {
        val rtl = viewContent(PageView.Two(0, 1), shapes::getValue, RightToLeft, gapPx = 2f)
        assertEquals(Size(1452f, 1000f), rtl.size)
        assertEquals(listOf(Rect(752f, 0f, 1452f, 1000f), Rect(0f, 0f, 750f, 1000f)), rtl.pages)
        val ltr = viewContent(PageView.Two(0, 1), shapes::getValue, LeftToRight, gapPx = 2f)
        assertEquals(listOf(Rect(0f, 0f, 700f, 1000f), Rect(702f, 0f, 1452f, 1000f)), ltr.pages)
    }

    @Test
    fun pagesScaleIntoScreenPixels() {
        val pair = viewContent(PageView.Two(0, 1), shapes::getValue, RightToLeft, gapPx = 2f)
        assertEquals(listOf(Rect(376f, 0f, 726f, 500f), Rect(0f, 0f, 375f, 500f)), pair.pagesAt(Scale(0.5f, 0.5f)))
    }

    @Test
    fun eachModeScalesTheWholeView() {
        val content = Size(1000f, 2000f)
        val viewport = Size(1000f, 1000f)
        assertEquals(Scale(0.5f, 0.5f), modeScale(ScaleMode.FitScreen, content, viewport, false))
        assertEquals(Scale(1f, 1f), modeScale(ScaleMode.FitWidth, content, viewport, false))
        assertEquals(Scale(0.5f, 0.5f), modeScale(ScaleMode.FitHeight, content, viewport, false))
        assertEquals(Scale(1f, 1f), modeScale(ScaleMode.Original, content, viewport, false))
        assertEquals(Scale(1f, 0.5f), modeScale(ScaleMode.Stretch, content, viewport, false))
    }

    @Test
    fun aWidePageAtFitHeightIsTwoScreensWide() {
        val scale = modeScale(ScaleMode.FitHeight, Size(1400f, 1000f), Size(700f, 1000f), false)
        assertEquals(2f, 1400f * scale.x / 700f)
    }

    @Test
    fun capAtOriginalStopsEveryModeAtOnePixel() {
        val small = Size(500f, 250f)
        val viewport = Size(1000f, 1000f)
        assertEquals(Scale(2f, 2f), modeScale(ScaleMode.FitScreen, small, viewport, false))
        for (mode in ScaleMode.entries) {
            val scale = modeScale(mode, small, viewport, capAtOriginal = true)
            assertEquals(true, scale.x <= 1f && scale.y <= 1f, "$mode gave $scale")
        }
        assertEquals(Scale(1f, 1f), modeScale(ScaleMode.Stretch, small, viewport, capAtOriginal = true))
    }

    @Test
    fun aPinchStartsOnceTheSpanChangesByTwentyDp() {
        val waiting = ZoomState().after(fitHalf, FingersDown(100f), SpanChanged(119f), SpanChanged(81f))
        assertEquals(1f, waiting.factor)
        val started = waiting.after(fitHalf, SpanChanged(120f))
        assertEquals(1f, started.factor)
        assertEquals(true, started.pinch?.active)
    }

    @Test
    fun aWiderPinchZoomsFaster() {
        val zoomed = ZoomState().after(fitHalf, FingersDown(100f), SpanChanged(120f), SpanChanged(240f))
        assertEquals(2f.pow(1.5f), zoomed.factor, 1e-4f)
    }

    @Test
    fun thePinchStaysBetweenHalfAndEightTimesTheFit() {
        assertEquals(8f, ZoomState().after(fitHalf, FingersDown(100f), SpanChanged(120f), SpanChanged(2400f)).factor)
        assertEquals(0.5f, ZoomState().after(fitHalf, FingersDown(100f), SpanChanged(120f), SpanChanged(10f)).factor)
    }

    @Test
    fun theLimitsCountFromTheFitScaleNotTheMode() {
        val fitWidth = ZoomLimits(mode = 1f, fit = 0.5f, capAtOriginal = false)
        assertEquals(0.25f, fitWidth.minFactor)
        assertEquals(4f, fitWidth.maxFactor)
    }

    @Test
    fun capAtOriginalAlsoCapsThePinch() {
        assertEquals(2f, ZoomLimits(mode = 0.5f, fit = 0.5f, capAtOriginal = true).maxFactor)
        assertEquals(1f, ZoomLimits(mode = 1f, fit = 1f, capAtOriginal = true).maxFactor)
    }

    @Test
    fun releasingBelowTheFitGoesBackToTheMode() {
        val below = ZoomState().after(fitHalf, FingersDown(100f), SpanChanged(120f), SpanChanged(100f), FingersUp)
        assertEquals(1f, below.factor)
        val fitWidth = ZoomLimits(mode = 1f, fit = 0.5f, capAtOriginal = false)
        val above = ZoomState().after(fitWidth, FingersDown(100f), SpanChanged(80f), SpanChanged(64f), FingersUp)
        assertEquals(0.8f.pow(1.5f), above.factor, 1e-4f)
    }

    @Test
    fun theZoomStaysAcrossTurnsUntilTheModeChanges() {
        val zoomed = ZoomState().after(fitHalf, FingersDown(100f), SpanChanged(120f), SpanChanged(240f), FingersUp)
        assertEquals(2f.pow(1.5f), zoomed.factor, 1e-4f)
        assertEquals(1f, zoomed.after(fitHalf, ModeChanged).factor)
    }

    @Test
    fun twoSecondFingerTapsWithinASecondReset() {
        val zoomed = ZoomState(factor = 3f)
        assertEquals(1f, zoomed.after(fitHalf, SecondFingerTap(0), SecondFingerTap(1_000)).factor)
        assertEquals(3f, zoomed.after(fitHalf, SecondFingerTap(0), SecondFingerTap(1_001)).factor)
    }

    @Test
    fun thePercentIsOverTheScaleMode() {
        assertEquals(150, ZoomState(factor = 1.5f).percent)
        assertEquals(100, ZoomState().percent)
    }
}
