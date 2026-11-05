package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.feature.reader.ZoomEvent.FingersDown
import io.github.yuubiz.hirari.feature.reader.ZoomEvent.FingersUp
import io.github.yuubiz.hirari.feature.reader.ZoomEvent.SecondFingerTap
import io.github.yuubiz.hirari.feature.reader.ZoomEvent.SpanChanged
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchChangedFrom
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchInput
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val SINGLE = ReaderConfig(layout = LayoutSettings(LayoutMode.Single, ReadingDirection.RightToLeft, coverAlone = false))
private val STRIP = ReaderConfig(layout = LayoutSettings(LayoutMode.Vertical, ReadingDirection.RightToLeft, coverAlone = false))

/** A scan with more pixels than the portrait screen shows of it: 1080 × 1620 at the scale mode. */
private val SCAN = PageShape.Known(3000, 4500)

private const val MB = 1024L * 1024L

/** A pinch that grows the span by [ratio] once it starts, so the zoom grows by [ratio] to the power 1.5. */
private fun ReaderState.pinched(ratio: Float, up: Boolean = true): ReaderState {
    val held = reduce(ReaderEvent.Zoom(FingersDown(100f)))
        .reduce(ReaderEvent.Zoom(SpanChanged(120f)))
        .reduce(ReaderEvent.Zoom(SpanChanged(120f * ratio)))
    return if (up) held.reduce(ReaderEvent.Zoom(FingersUp)) else held
}

private fun ReaderState.preparedSize(view: PageView = current): PixelSize = prefetchTarget(view)!!.sizes.single()

class ZoomReadingTest {
    @Test
    fun aSettledZoomPreparesTheCurrentPageAtTheZoomAndItsNeighborsAtTheScaleMode() {
        val state = readerOf(List(3) { SCAN }, SINGLE, PORTRAIT)
        assertEquals(PixelSize(1080, 1620), state.preparedSize())
        val zoomed = state.pinched(1.5625f)
        assertEquals(1.953125f, zoomed.zoom.factor, 1e-4f)
        assertEquals(PixelSize(2109, 3164), zoomed.preparedSize())
        assertEquals(PixelSize(1080, 1620), zoomed.preparedSize(PageView.One(1)))
        assertEquals(emptyMap(), zoomed.tileWindows(), "a page read whole again needs no tiles")
    }

    @Test
    fun aPinchInProgressKeepsThePreparedSizeUntilTheFingersComeUp() {
        val held = readerOf(listOf(SCAN), SINGLE, PORTRAIT).pinched(1.5625f, up = false)
        assertEquals(1.953125f, held.zoom.factor, 1e-4f)
        assertEquals(1f, held.zoom.settledFactor)
        assertEquals(PixelSize(1080, 1620), held.preparedSize())
        val up = held.reduce(ReaderEvent.Zoom(FingersUp))
        assertEquals(PixelSize(2109, 3164), up.preparedSize())
        val again = up.reduce(ReaderEvent.Zoom(FingersDown(100f))).reduce(ReaderEvent.Zoom(SpanChanged(120f))).reduce(ReaderEvent.Zoom(SpanChanged(200f)))
        assertEquals(PixelSize(2109, 3164), again.preparedSize(), "the next pinch keeps the last settled size until it ends")
    }

    @Test
    fun aPageThatWouldNotFitOneBitmapComesAsTilesOverItsPreparedPage() {
        val zoomed = readerOf(listOf(SCAN), SINGLE, PORTRAIT).pinched(2.25f)
        assertEquals(3.375f, zoomed.zoom.factor, 1e-4f)
        assertEquals(ZoomRead.Tiles, zoomed.zoomRead, "3000 × 4500 at full size is longer than 4096")
        assertEquals(PixelSize(1080, 1620), zoomed.preparedSize())
        val window = zoomed.tileWindows().getValue(0)
        assertEquals(1080.0 * 3.375 / 3000, window.scale, 1e-3)
        assertEquals(PixelSize(3000, 4500), window.image)
        assertTrue(window.shown.width in 889..890, "the screen's width in image pixels, rounded out: ${window.shown}")
        assertTrue(window.shown.left > 0 && window.shown.right < 3000, "only what the screen shows: ${window.shown}")
    }

    @Test
    fun aZoomWhosePagesExceedAQuarterOfTheBudgetReadsTiles() {
        val small = readerOf(listOf(SCAN), SINGLE, PORTRAIT).copy(zoomReads = ZoomReads(budgetBytes = 64 * MB)).pinched(1.5625f)
        assertEquals(ZoomRead.Tiles, small.zoomRead, "2109 × 3164 takes 26 MB, more than 16 MB")
        assertEquals(PixelSize(1080, 1620), small.preparedSize())
        assertEquals(setOf(0), small.tileWindows().keys)
    }

    @Test
    fun aZoomBackToTheScaleModeReadsNothingAgain() {
        val zoomed = readerOf(listOf(SCAN), SINGLE, PORTRAIT).pinched(2.25f)
        val reset = zoomed.reduce(ReaderEvent.Zoom(FingersDown(100f)))
            .reduce(ReaderEvent.Zoom(SecondFingerTap(0)))
            .reduce(ReaderEvent.Zoom(SecondFingerTap(100)))
        assertEquals(ZoomRead.None, reset.zoomRead, "the reset takes effect while the fingers are still down")
        assertEquals(emptyMap(), reset.reduce(ReaderEvent.Zoom(FingersUp)).tileWindows())
        val snapped = readerOf(listOf(SCAN), SINGLE, PORTRAIT).pinched(0.64f)
        assertEquals(1f, snapped.zoom.factor, "a release below the fit goes back to the scale mode")
        assertEquals(PixelSize(1080, 1620), snapped.preparedSize())
    }

    @Test
    fun anImageWithNoMorePixelsIsNotReadAgainButADrawnPageIs() {
        val small = PageShape.Known(800, 1200)
        val zoomed = readerOf(listOf(small), SINGLE, PORTRAIT).pinched(1.5625f)
        assertEquals(ZoomRead.None, zoomed.zoomRead, "its prepared page already has all 800 × 1200 pixels")
        assertEquals(PixelSize(1080, 1620), zoomed.preparedSize())
        val drawn = zoomed.copy(zoomReads = ZoomReads(drawn = true))
        assertEquals(PixelSize(2109, 3164), drawn.preparedSize())
    }

    @Test
    fun aTurnedPageThatWouldNotFitIsReadWholeAsLargeAsOneBitmapHolds() {
        val zoomed = readerOf(listOf(SCAN), SINGLE.copy(rotation = Rotation.Quarter), PORTRAIT).pinched(4f)
        val read = assertIs<ZoomRead.Whole>(zoomed.zoomRead)
        assertEquals(4096, read.sizes.single().width, "4500 × 3000 turned, shrunk to fit 4096")
        assertEquals(emptyMap(), zoomed.tileWindows(), "a turned page has no tiles")
    }

    @Test
    fun aTurnWhileZoomedReadsTheNewPageAtTheZoomAndTheOldOneAtTheScaleMode() {
        val zoomed = readerOf(List(3) { SCAN }, SINGLE, PORTRAIT).pinched(1.5625f)
        val turned = zoomed.reduce(ReaderEvent.Act(ReaderAction.TurnView(Turn.Forward), 0))
        assertEquals(PageView.One(1), turned.current)
        assertEquals(PixelSize(2109, 3164), turned.preparedSize())
        assertEquals(PixelSize(1080, 1620), turned.preparedSize(PageView.One(0)))
        val tiled = readerOf(List(3) { SCAN }, SINGLE, PORTRAIT).pinched(2.25f)
        assertEquals(setOf(0), tiled.tileWindows().keys)
        val turning = tiled.reduce(ReaderEvent.Act(ReaderAction.TurnView(Turn.Forward), 0))
        assertEquals(emptyMap(), turning.tileWindows(), "no tiles while the views move")
        assertEquals(setOf(1), turning.reduce(ReaderEvent.Tick(Long.MAX_VALUE)).tileWindows().keys)
    }

    @Test
    fun aSettledZoomOfTheStripShowsItsPagesAsTiles() {
        val strip = readerOf(List(3) { SCAN }, STRIP, PORTRAIT)
        assertEquals(emptyMap(), strip.tileWindows(), "a page no longer than 1:3 has no tiles at the width of the screen")
        val zoomed = strip.pinched(1.5625f)
        val window = zoomed.tileWindows().getValue(zoomed.current.pages.first())
        assertEquals(1080.0 * 1.953125 / 3000, window.scale, 1e-3)
        assertEquals(PixelSize(1080, 1620), zoomed.preparedSize(), "the strip keeps its pages at the width of the screen")
    }

    @Test
    fun aPageShownAtItsOwnSizeIsSharpenedByTilesWhenItsPreparedPageWasDecodedShorter() {
        val original = readerOf(listOf(SCAN), SINGLE.copy(scaleMode = ScaleMode.Original), PORTRAIT)
        assertEquals(PixelSize(3000, 4500), original.preparedSize(), "laid out at its own size")
        val zoomed = original.pinched(1.5625f)
        assertEquals(ZoomRead.Tiles, zoomed.zoomRead, "the decode of 3000 × 4500 stops at 4096 on the long side")
        assertEquals(setOf(0), zoomed.tileWindows().keys)
    }

    @Test
    fun aPageThatHasAllItsPixelsAtTheScaleModeStaysAsItIsWhenZoomed() {
        val shape = PageShape.Known(3000, 3500)
        val zoomed = readerOf(listOf(shape), SINGLE.copy(scaleMode = ScaleMode.Original), PORTRAIT).pinched(1.5625f)
        assertEquals(ZoomRead.None, zoomed.zoomRead, "its prepared page has every pixel of 3000 × 3500")
    }

    @Test
    fun aPageThatCannotHaveTilesWithinTheirShareIsReadWholeAsLargeAsFits() {
        val zoomed = readerOf(listOf(SCAN), SINGLE, PORTRAIT).pinched(2.25f)
        assertEquals(ZoomRead.Tiles, zoomed.zoomRead)
        val untiled = zoomed.copy(zoomReads = zoomed.zoomReads.copy(wholeOnly = setOf(0)))
        val read = assertIs<ZoomRead.Whole>(untiled.zoomRead)
        assertEquals(PixelSize(2731, 4096), read.sizes.single(), "as long as one bitmap holds")
        assertEquals(emptyMap(), untiled.tileWindows())
        assertEquals(PixelSize(2731, 4096), untiled.preparedSize())
    }

    @Test
    fun aPinchInProgressWithdrawsTheZoomedTargetOfThePrefetchPlanUntilTheFingersLift() {
        val state = readerOf(listOf(SCAN), SINGLE, PORTRAIT)
        val settled = state.pinched(1.5625f)
        assertTrue(!settled.prefetchInput().pinching)
        val pinching = settled.pinched(1.2f, up = false)
        assertTrue(pinching.prefetchInput().pinching)
        assertTrue(pinching.prefetchChangedFrom(settled), "fingers moving the zoom stop the read for the last one")
        assertTrue(!pinching.reduce(ReaderEvent.Zoom(SpanChanged(150f))).prefetchChangedFrom(pinching), "and nothing changes while they move")
        val up = pinching.reduce(ReaderEvent.Zoom(FingersUp))
        assertTrue(up.prefetchChangedFrom(pinching), "the read for the new zoom starts when they lift")
        assertTrue(!up.prefetchInput().pinching)
    }

    @Test
    fun theWholePageOfAPartLiesWhereTheLeftOutPartsWouldBe() {
        val part = PagePart(0.5f, 0.25f, 1f, 0.75f)
        assertEquals(Rect(-100f, -100f, 100f, 300f), part.wholeAt(Rect(0f, 0f, 100f, 200f)))
    }
}
