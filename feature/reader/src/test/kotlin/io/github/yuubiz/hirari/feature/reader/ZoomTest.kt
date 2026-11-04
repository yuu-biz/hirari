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
    private val fitHalf = ZoomLimits(mode = Scale(0.5f, 0.5f), fit = 0.5f, capAtOriginal = false)

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
    fun fitPageGivesEachPageOfAPairAPortraitScreen() {
        val portrait = Size(1080f, 1920f)
        assertEquals(Scale(1.08f, 1.08f), modeScale(ScaleMode.FitPage, Size(2000f, 1500f), portrait, capAtOriginal = false))
        assertEquals(Scale(1.08f, 1.08f), modeScale(ScaleMode.FitPage, Size(1000f, 1500f), portrait, capAtOriginal = false))
        assertEquals(Scale(1.08f, 1.08f), modeScale(ScaleMode.FitPage, Size(1000f, 1000f), Size(1080f, 1280f), capAtOriginal = false), "a square page counts as one frame")
        assertEquals(Scale(1f, 1f), modeScale(ScaleMode.FitPage, Size(2000f, 1500f), portrait, capAtOriginal = true))
    }

    @Test
    fun fitPageFitsAPairIntoALandscapeScreenAndAPageIntoHalfOfIt() {
        val landscape = Size(1920f, 1080f)
        assertEquals(Scale(0.72f, 0.72f), modeScale(ScaleMode.FitPage, Size(2000f, 1500f), landscape, capAtOriginal = false))
        assertEquals(Scale(0.8f, 0.8f), modeScale(ScaleMode.FitPage, Size(1200f, 1300f), landscape, capAtOriginal = false), "half the screen is 960 wide")
        assertEquals(modeScale(ScaleMode.FitScreen, Size(2000f, 1500f), landscape, false), modeScale(ScaleMode.FitPage, Size(2000f, 1500f), landscape, false))
    }

    @Test
    fun aPairInFitPageOnAPortraitScreenIsReadRightPageThenLeftPage() {
        val state = readerOf(List(2) { PageShape.Known(1000, 1500) }, spreadConfig(), PORTRAIT)
        val geometry = state.geometry()!!
        assertEquals(1080f, geometry.pages[0].width, 2f)
        assertEquals(geometry.pages[0].width, geometry.pages[1].width, 0.5f)
        assertEquals(2 * 1080f, geometry.size.width, 0.5f)
        val stops = geometry.stops(state.config).stops
        assertEquals(listOf(0, 1), stops.map { it.part })
        assertEquals(listOf(1080f, 0f), stops.map { it.point.x }, "the right page whole, then the left page whole")
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
        val fitWidth = ZoomLimits(mode = Scale(1f, 1f), fit = 0.5f, capAtOriginal = false)
        assertEquals(0.25f, fitWidth.minFactor)
        assertEquals(4f, fitWidth.maxFactor)
    }

    @Test
    fun capAtOriginalAlsoCapsThePinch() {
        assertEquals(2f, ZoomLimits(mode = Scale(0.5f, 0.5f), fit = 0.5f, capAtOriginal = true).maxFactor)
        assertEquals(1f, ZoomLimits(mode = Scale(1f, 1f), fit = 1f, capAtOriginal = true).maxFactor)
    }

    @Test
    fun capAtOriginalHoldsTheLargerAxisOfAStretch() {
        val stretch = modeScale(ScaleMode.Stretch, Size(1000f, 2000f), Size(1000f, 1000f), capAtOriginal = true)
        val fit = modeScale(ScaleMode.FitScreen, Size(1000f, 2000f), Size(1000f, 1000f), capAtOriginal = true).smaller
        assertEquals(1f, ZoomLimits(stretch, fit, capAtOriginal = true).maxFactor)
        assertEquals(8f, ZoomLimits(stretch, fit, capAtOriginal = false).maxFactor)
    }

    @Test
    fun releasingBelowTheFitGoesBackToTheMode() {
        val below = ZoomState().after(fitHalf, FingersDown(100f), SpanChanged(120f), SpanChanged(100f), FingersUp)
        assertEquals(1f, below.factor)
        val fitWidth = ZoomLimits(mode = Scale(1f, 1f), fit = 0.5f, capAtOriginal = false)
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
    fun aResetDuringAPinchStaysUntilTheSpanMovesAgain() {
        val reset = ZoomState(factor = 3f).after(fitHalf, FingersDown(100f), SpanChanged(120f), SecondFingerTap(0), SecondFingerTap(500))
        assertEquals(1f, reset.factor)
        val still = reset.after(fitHalf, SpanChanged(120f), SpanChanged(139f))
        assertEquals(1f, still.factor)
        val resumed = still.after(fitHalf, SpanChanged(140f), SpanChanged(280f))
        assertEquals(2f.pow(1.5f), resumed.factor, 1e-4f)
    }

    @Test
    fun thePercentIsOverTheScaleMode() {
        assertEquals(150, ZoomState(factor = 1.5f).percent)
        assertEquals(100, ZoomState().percent)
    }
}
