package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.KeyCodes
import io.github.yuubiz.hirari.core.model.KeyResult
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.SCROLL_STEP_SHARE
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.StripPosition
import io.github.yuubiz.hirari.core.model.TrimStrength
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchInput
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchTarget
import io.github.yuubiz.hirari.feature.reader.prefetch.shownViews
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A webtoon segment: 800 wide, so 1080 / 800 × 1600 = 2160 pixels tall on the portrait screen. */
private val SEGMENT = PageShape.Known(800, 1600)
private val VERTICAL = ReaderConfig(layout = LayoutSettings(LayoutMode.Vertical, ReadingDirection.RightToLeft, coverAlone = true))
private const val SEGMENT_PX = 1080.0 * 1600 / 800

private fun strip(count: Int, config: ReaderConfig = VERTICAL, start: Int = 0) = readerOf(List(count) { SEGMENT }, config, PORTRAIT, start)

private fun ReaderState.act(action: ReaderAction, nowMs: Long = 0) = reduce(ReaderEvent.Act(action, nowMs))

private fun ReaderState.settle() = reduce(ReaderEvent.Tick(Long.MAX_VALUE))

private val ReaderState.top: Double get() = scroll!!.top(strip, stripScreen)

private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 0.01, "expected $expected, got $actual")

class VerticalReaderTest {
    @Test
    fun theStripOpensAtTheSavedPageAndShareOfIt() {
        val state = ReaderState.open(10, 3, VERTICAL, PORTRAIT, offset = 0.25f)
        assertEquals(StripPosition(3, 0.25f), state.scroll!!.anchor)
        assertEquals(PageView.One(3), state.current)
        assertNull(ReaderState.open(10, 3, VERTICAL.copy(layout = VERTICAL.layout.copy(mode = LayoutMode.Single)), PORTRAIT).scroll)
    }

    @Test
    fun aTapScrollsNineTenthsOfTheScreenAtTheStopSpeedAndThePageFollows() {
        val state = strip(10)
        val stepping = state.act(ReaderAction.TurnView(Turn.Forward), nowMs = 100)
        assertNotNull(stepping.scroll!!.motion)
        assertEquals(PageView.One(0), stepping.current)
        val done = stepping.settle()
        near(PORTRAIT.height * SCROLL_STEP_SHARE.toDouble(), done.top)
        assertEquals(PageView.One(0), done.current)
        val twice = done.act(ReaderAction.StepStops(Turn.Forward)).settle()
        assertEquals(PageView.One(1), twice.current, "the top of the screen is on the second page")
        assertEquals(listOf(Turn.Forward), twice.recentTurns)
    }

    @Test
    fun theNextInputEndsARunningStepAtOnce() {
        val state = strip(10).act(ReaderAction.TurnView(Turn.Forward), nowMs = 0)
        val again = state.act(ReaderAction.TurnView(Turn.Forward), nowMs = 10).settle()
        near(2.0 * PORTRAIT.height * SCROLL_STEP_SHARE, again.top)
    }

    @Test
    fun aDragScrollsAndAFlingGlidesOnUntilItStops() {
        val state = strip(30).reduce(ReaderEvent.Pan(0f, -500f))
        near(500.0, state.top)
        val flung = state.reduce(ReaderEvent.Fling(-6000f, nowMs = 0))
        assertNotNull(flung.scroll!!.motion)
        val later = flung.reduce(ReaderEvent.Tick(300))
        assertTrue(later.top > state.top)
        val stopped = later.settle()
        assertNull(stopped.scroll!!.motion)
        assertTrue(stopped.top > later.top)
        val caught = later.reduce(ReaderEvent.Pan(0f, 0f))
        near(later.top, caught.top)
        assertNull(caught.scroll!!.motion)
    }

    @Test
    fun aJumpPutsTheTopOfThePageAtTheTopOfTheScreen() {
        val state = strip(10).reduce(ReaderEvent.GoTo(4))
        near(4 * SEGMENT_PX, state.top)
        assertEquals(PageView.One(4), state.current)
        val first = state.act(ReaderAction.First)
        near(0.0, first.top)
    }

    @Test
    fun theLastPageIsCurrentOnceTheEndIsOnScreenAndTheBookIsReadThen() {
        val last = strip(10).act(ReaderAction.Last)
        near(9 * SEGMENT_PX, last.top)
        assertFalse(last.showsLastView, "the top of the last page is not its end")
        val short = readerOf(List(9) { SEGMENT } + PageShape.Known(800, 400), VERTICAL, PORTRAIT).reduce(ReaderEvent.Pan(0f, -1e6f))
        assertEquals(8, short.scroll!!.anchor.page)
        assertEquals(PageView.One(9), short.current, "a short last page is current at the end")
        val state = strip(10).reduce(ReaderEvent.Pan(0f, -1e6f))
        assertTrue(state.scroll!!.endInView(state.strip, state.stripScreen))
        assertEquals(PageView.One(9), state.current)
        assertTrue(state.showsLastView)
        val log = ProgressLog(BookId("b"), 10, opened = null)
        assertEquals(ReadState.Read, log.next(state, Instant.EPOCH)?.state)
    }

    @Test
    fun steppingPastTheEndRunsIntoItOnlyOnceThePagesOnScreenAreKnown() {
        val end = strip(3).reduce(ReaderEvent.Pan(0f, -1e6f))
        val hit = end.act(ReaderAction.TurnView(Turn.Forward), nowMs = 7)
        assertEquals(BookEdge.End, hit.edgeHit?.edge)
        assertEquals(BookEdge.Start, strip(3).act(ReaderAction.TurnView(Turn.Back)).edgeHit?.edge)
        val unknown = ReaderState.open(1, 0, VERTICAL, PORTRAIT)
        assertNull(unknown.act(ReaderAction.TurnView(Turn.Forward)).edgeHit)
    }

    @Test
    fun learningTheShapeOfAPageAboveKeepsTheScreenOnItsPage() {
        val state = ReaderState.open(6, 3, VERTICAL, PORTRAIT, offset = 0.5f)
            .reduce(ReaderEvent.ShapeLearned(3, SEGMENT))
            .reduce(ReaderEvent.ShapeLearned(4, SEGMENT))
            .reduce(ReaderEvent.ShapeLearned(5, SEGMENT))
        val before = state.top - state.strip.top(3) * state.stripScreen.scale
        val learned = state.reduce(ReaderEvent.ShapeLearned(1, PageShape.Known(800, 8000)))
        assertEquals(StripPosition(3, 0.5f), learned.scroll!!.anchor)
        near(before, learned.top - learned.strip.top(3) * learned.stripScreen.scale)
    }

    @Test
    fun theSavedPositionIsThePageAtTheTopAndHowFarDownItIs() {
        val log = ProgressLog(BookId("b"), 10, opened = null)
        val state = strip(10).reduce(ReaderEvent.Pan(0f, -(SEGMENT_PX * 2.5).toFloat()))
        val saved = assertNotNull(log.next(state, Instant.EPOCH)).position as ReadingPosition.Image
        assertEquals(2, saved.viewStart)
        assertTrue(abs(saved.offset - 0.5f) < 1e-4f, "offset ${saved.offset}")
        val reopened = ReaderState.open(10, saved.viewStart, VERTICAL, PORTRAIT, saved.offset)
        assertNull(ProgressLog(BookId("b"), 10, BookProgress(BookId("b"), ReadState.Reading, saved, Instant.EPOCH)).next(reopened, Instant.EPOCH))
    }

    @Test
    fun anotherScreenKeepsThePlaceAndDropsTheZoom() {
        val state = strip(10).reduce(ReaderEvent.Pan(0f, -3000f))
        val turned = state.reduce(ReaderEvent.ViewportChanged(LANDSCAPE))
        assertEquals(state.scroll!!.anchor, turned.scroll!!.anchor)
        assertEquals(1f, turned.zoom.factor)
    }

    @Test
    fun switchingLayoutsKeepsThePage() {
        val spread = strip(10, spreadConfig()).reduce(ReaderEvent.GoTo(4))
        val vertical = spread.reduce(ReaderEvent.ConfigChanged(spread.config.copy(layout = VERTICAL.layout)))
        assertEquals(StripPosition(4, 0f), vertical.scroll!!.anchor)
        val back = vertical.reduce(ReaderEvent.Pan(0f, -10f)).reduce(ReaderEvent.ConfigChanged(spread.config))
        assertNull(back.scroll)
        assertTrue(4 in back.current.pages)
        val zoomed = spread.reduce(ReaderEvent.Zoom(ZoomEvent.FingersDown(100f))).reduce(ReaderEvent.Zoom(ZoomEvent.SpanChanged(130f)))
            .reduce(ReaderEvent.Zoom(ZoomEvent.SpanChanged(200f)))
        assertTrue(zoomed.zoom.factor > 1f)
        assertEquals(1f, zoomed.reduce(ReaderEvent.ConfigChanged(spread.config.copy(layout = VERTICAL.layout))).zoom.factor)
    }

    @Test
    fun aPinchZoomsTheStripAboutTheFingersAndALooseOneGoesBackToTheWidth() {
        val state = strip(10).reduce(ReaderEvent.Pan(0f, -3000f))
        val focus = Point(300f, 900f)
        val down = state.reduce(ReaderEvent.Zoom(ZoomEvent.FingersDown(100f), focus))
        val zoomed = down.reduce(ReaderEvent.Zoom(ZoomEvent.SpanChanged(130f), focus)).reduce(ReaderEvent.Zoom(ZoomEvent.SpanChanged(200f), focus))
        assertTrue(zoomed.zoom.factor > 1f)
        near((state.top + focus.y) / state.stripScreen.scale, (zoomed.top + focus.y) / zoomed.stripScreen.scale)
        val wide = zoomed.reduce(ReaderEvent.Pan(-50f, 0f))
        assertTrue(wide.scroll!!.left(wide.stripScreen) > 0f, "a zoomed strip pans across")
        val shrunk = down.reduce(ReaderEvent.Zoom(ZoomEvent.SpanChanged(70f), focus)).reduce(ReaderEvent.Zoom(ZoomEvent.SpanChanged(60f), focus))
        assertTrue(shrunk.zoom.factor < 1f)
        assertEquals(1f, shrunk.reduce(ReaderEvent.Zoom(ZoomEvent.FingersUp)).zoom.factor)
    }

    @Test
    fun everyPageIsPreparedAsWideAsTheScreenWhateverTheScaleModeAndTrim() {
        val state = strip(4, VERTICAL.copy(scaleMode = ScaleMode.FitHeight, capAtOriginal = true, trim = TrimStrength.Strongest))
        val target = assertNotNull(state.prefetchTarget(PageView.One(1)))
        assertEquals(1080, target.sizes.single().width)
        assertEquals(PagePart.Whole, target.steps.single().part)
        assertFalse(state.prefetchInput().readsMargins(1))
    }

    @Test
    fun everyPageOnScreenIsShown() {
        val state = strip(10).reduce(ReaderEvent.Pan(0f, -(SEGMENT_PX * 0.9).toFloat()))
        assertEquals(setOf(PageView.One(0), PageView.One(1)), state.shownViews)
        assertEquals(0..1, state.scroll!!.visible(state.strip, state.stripScreen))
    }

    @Test
    fun autoPlayStepsDownTheStripAndStopsAtTheEnd() {
        val playing = strip(2).reduce(ReaderEvent.AutoPlayStarted(nowMs = 0))
        val stepped = playing.reduce(ReaderEvent.AutoPlayDue(playing.autoPlay!!.dueAtMs))
        assertNotNull(stepped.scroll!!.motion)
        assertNotNull(stepped.autoPlay)
        var state = stepped.settle()
        repeat(4) { state = state.reduce(ReaderEvent.AutoPlayDue(state.autoPlay?.dueAtMs ?: return@repeat)).settle() }
        assertNull(state.autoPlay)
        assertTrue(state.showsLastView)
    }

    @Test
    fun aStepTicksAndADragDoesNot() {
        val state = strip(10, VERTICAL.copy(turnHaptics = true))
        val event = ReaderEvent.Act(ReaderAction.TurnView(Turn.Forward), 0)
        assertTrue(turnTicks(event, state, state.reduce(event)))
        val pan = ReaderEvent.Pan(0f, -3000f)
        assertFalse(turnTicks(pan, state, state.reduce(pan)))
    }

    @Test
    fun upAndDownKeysScrollTheStripInsteadOfShifting() {
        assertEquals(KeyResult.Act(ReaderAction.StepStops(Turn.Forward)), VERTICAL.keys.onKey(KeyCodes.DPAD_DOWN, pressed = true))
        assertEquals(KeyResult.Act(ReaderAction.StepStops(Turn.Back)), VERTICAL.keys.onKey(KeyCodes.DPAD_UP, pressed = true))
        assertEquals(KeyResult.Act(ReaderAction.Shift(forward = true)), spreadConfig().keys.onKey(KeyCodes.DPAD_DOWN, pressed = true))
    }

    @Test
    fun aLongSoundScrollsTheStripByTheDistanceUpToTheEnd() {
        val state = strip(3)
        val pan = assertNotNull(state.stripPan(Turn.Forward, 300f))
        near(300.0, state.reduce(pan).top)
        val end = state.reduce(assertNotNull(state.stripPan(Turn.Forward, 1e7f)))
        assertTrue(end.scroll!!.endInView(end.strip, end.stripScreen))
        assertNull(end.stripPan(Turn.Forward, 10f))
        assertNull(state.stripPan(Turn.Back, 10f))
        assertNull(readerOf(List(2) { TALL }).stripPan(Turn.Forward, 10f))
    }

    @Test
    fun aShiftDoesNothing() {
        val state = strip(10).reduce(ReaderEvent.Pan(0f, -100f))
        assertEquals(state, state.act(ReaderAction.Shift(forward = true)))
    }

    @Test
    fun turningThePagesKeepsTheStripOnItsPage() {
        val state = strip(10).reduce(ReaderEvent.GoTo(3))
        val turned = state.reduce(ReaderEvent.ConfigChanged(state.config.copy(rotation = Rotation.Quarter)))
        assertEquals(StripPosition(3, 0f), turned.scroll!!.anchor)
        assertEquals(PageView.One(3), turned.current)
    }
}
