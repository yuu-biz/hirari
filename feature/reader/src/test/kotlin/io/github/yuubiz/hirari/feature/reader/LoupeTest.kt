package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.LoupeFrame
import io.github.yuubiz.hirari.core.model.LoupeZoom
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.TouchOutcome
import io.github.yuubiz.hirari.core.model.TouchState
import io.github.yuubiz.hirari.core.model.TurnAreaTouch
import io.github.yuubiz.hirari.feature.reader.Quadrant.BottomLeft
import io.github.yuubiz.hirari.feature.reader.Quadrant.BottomRight
import io.github.yuubiz.hirari.feature.reader.Quadrant.TopLeft
import io.github.yuubiz.hirari.feature.reader.Quadrant.TopRight
import io.github.yuubiz.hirari.core.model.TouchEvent.Down
import io.github.yuubiz.hirari.core.model.TouchEvent.Hold
import io.github.yuubiz.hirari.core.model.TouchEvent.Move
import io.github.yuubiz.hirari.core.model.TouchEvent.Up
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val SCREEN = Size(1080f, 1920f)
private const val DENSITY = 3f

class LoupeTest {
    @Test
    fun theLoupeComesUpInTheQuarterAcrossTheMiddleFromTheFinger() {
        assertEquals(BottomRight, Loupe.at(Point(100f, 100f), SCREEN).quadrant)
        assertEquals(TopLeft, Loupe.at(Point(1000f, 1800f), SCREEN).quadrant)
        assertEquals(BottomLeft, Loupe.at(Point(1000f, 100f), SCREEN).quadrant)
        assertEquals(listOf(BottomRight, BottomLeft, TopRight, TopLeft), Quadrant.entries.map { it.opposite })
    }

    @Test
    fun theFrameSitsInTheOuterCornerOfItsQuarter() {
        assertEquals(Rect(0f, 0f, 1080f * 7 / 16, 1920f * 7 / 16), Loupe.cornerRect(TopLeft, SCREEN, LoupeFrame.Small))
        assertEquals(Rect(1080f / 2, 1920f / 2, 1080f, 1920f), Loupe.cornerRect(BottomRight, SCREEN, LoupeFrame.Medium))
        val large = Loupe.cornerRect(TopRight, SCREEN, LoupeFrame.Large)
        assertEquals(1080f * 7 / 16, large.left, 0.01f)
        assertEquals(1920f * 9 / 16, large.bottom, 0.01f)
    }

    @Test
    fun enteringTheLoupesQuarterSendsItAcrossIn150Ms() {
        val loupe = Loupe.at(Point(300f, 500f), SCREEN)
        assertEquals(BottomRight, loupe.quadrant)
        val moved = loupe.moved(Point(800f, 1400f), SCREEN, DENSITY, nowMs = 1000)
        assertEquals(TopLeft, moved.quadrant)
        val move = assertNotNull(moved.move)
        assertEquals(BottomRight, move.from)
        val from = Loupe.cornerRect(BottomRight, SCREEN, LoupeFrame.Small)
        val to = Loupe.cornerRect(TopLeft, SCREEN, LoupeFrame.Small)
        assertEquals(from, moved.rectAt(1000, SCREEN, LoupeFrame.Small))
        assertEquals((from.left + to.left) / 2, moved.rectAt(1000 + LOUPE_MOVE_MS / 2, SCREEN, LoupeFrame.Small).left, 0.5f)
        assertEquals(to, moved.rectAt(1000 + LOUPE_MOVE_MS, SCREEN, LoupeFrame.Small))
        assertNull(moved.at(1000 + LOUPE_MOVE_MS).move)
        assertEquals(moved, moved.at(1000 + LOUPE_MOVE_MS - 1))
    }

    @Test
    fun afterAMoveTheFingerMustClearTheMiddleLinesBeforeTheNextOne() {
        val moved = Loupe.at(Point(300f, 500f), SCREEN).moved(Point(560f, 980f), SCREEN, DENSITY, 0)
        assertEquals(TopLeft, moved.quadrant)
        val near = moved.moved(Point(530f, 950f), SCREEN, DENSITY, 10)
        assertEquals(TopLeft, near.quadrant, "within 24 dp of the middle, the finger in the loupe's quarter does not move it")
        val clear = near.moved(Point(540f - 24 * DENSITY, 960f - 24 * DENSITY), SCREEN, DENSITY, 20)
        assertEquals(BottomRight, clear.quadrant)
    }

    @Test
    fun movingInsideItsOwnQuarterLeavesTheLoupeWhereItIs() {
        val loupe = Loupe.at(Point(300f, 500f), SCREEN)
        val moved = loupe.moved(Point(200f, 1500f), SCREEN, DENSITY, 0)
        assertEquals(BottomRight, moved.quadrant)
        assertNull(moved.move)
        assertEquals(Point(200f, 1500f), moved.finger)
    }

    @Test
    fun magnificationsOfTheScreenAreFixedAndThoseOfTheSourceDependOnTheDisplay() {
        assertEquals(2f, LoupeZoom.View200.magnification(0.3f))
        assertEquals(1.5f, LoupeZoom.View150.magnification(4f))
        assertEquals(2f, LoupeZoom.Source100.magnification(0.5f))
        assertEquals(6f, LoupeZoom.Source300.magnification(0.5f))
        assertEquals(1f, LoupeZoom.Source100.magnification(2f), "an image shown larger than its own size is not shrunk")
    }

    @Test
    fun theFingerShowsAtTheCenterOfTheLoupe() {
        val view = LoupeView(Rect(0f, 0f, 400f, 800f), Point(700f, 1500f), 2f)
        assertEquals(Point(200f, 400f), view.shown(Point(700f, 1500f)))
        assertEquals(Point(220f, 380f), view.shown(Point(710f, 1490f)))
        assertEquals(Rect(600f, 1300f, 800f, 1700f), view.source)
    }

    @Test
    fun aHoldStillForTheLoupeTimeBringsItUpAndItsReleaseIsNoTap() {
        val touch = TurnAreaTouch(ReadingDirection.RightToLeft, mirrored = false, swipeToTurn = true, touchSlop = 8f, holdMs = 800)
        val origin = Point(200f, 400f)
        val pressed = touch.reduce(TouchState.Idle, Down(origin, 0, emptySet())).state
        assertEquals(pressed, touch.reduce(pressed, Hold(799)).state)
        val shown = touch.reduce(pressed, Hold(800))
        assertEquals(TouchOutcome.ShowLoupe(origin), shown.outcome)
        val following = touch.reduce(shown.state, Move(Point(300f, 450f))).state
        assertEquals(TouchState.Loupe(Point(300f, 450f)), following)
        assertEquals(TouchOutcome.HideLoupe, touch.reduce(following, Up(Point(300f, 450f), 2000, 0f)).outcome)
        assertEquals(TouchOutcome.Tap(origin), touch.reduce(pressed, Up(origin, 799, 0f)).outcome)
    }

    @Test
    fun aDragBeforeTheHoldIsNoLoupe() {
        val touch = TurnAreaTouch(ReadingDirection.RightToLeft, mirrored = false, swipeToTurn = false, touchSlop = 8f, holdMs = 800)
        val pressed = touch.reduce(TouchState.Idle, Down(Point(200f, 400f), 0, emptySet())).state
        val scrolling = touch.reduce(pressed, Move(Point(200f, 450f))).state
        assertNull(touch.reduce(scrolling, Hold(900)).outcome)
    }

    @Test
    fun theReaderHoldsTheLoupeUntilTheFingerGoesUp() {
        val state = readerOf(List(2) { TALL }, spreadConfig(), PORTRAIT).reduce(ReaderEvent.LoupeShown(Point(100f, 1000f)))
        val loupe = assertNotNull(state.loupe)
        assertEquals(TopRight, loupe.quadrant)
        val view = assertNotNull(state.loupeView(0))
        assertEquals(2f, view.magnification)
        assertEquals(Loupe.cornerRect(TopRight, PORTRAIT.size, LoupeFrame.Small), view.rect)
        assertNull(state.reduce(ReaderEvent.LoupeHidden).loupe)
        assertNull(state.reduce(ReaderEvent.ViewportChanged(LANDSCAPE)).loupe)
    }

    @Test
    fun aHoldStopsAutoPlay() {
        val playing = readerOf(List(2) { TALL }).reduce(ReaderEvent.AutoPlayStarted(0))
        assertNull(playing.reduce(ReaderEvent.LoupeShown(Point(10f, 10f))).autoPlay)
    }

    @Test
    fun theSourceMagnificationsReadThePageUnderTheFingerAtItsOwnSize() {
        val config = spreadConfig().copy(loupeZoom = LoupeZoom.Source100, scaleMode = ScaleMode.FitScreen)
        val big = PageShape.Known(3000, 4500)
        val state = readerOf(List(2) { big }, config, PORTRAIT).reduce(ReaderEvent.LoupeShown(Point(100f, 1000f)))
        assertEquals(PageView.Two(0, 1), state.current)
        val source = assertNotNull(state.loupeSource())
        assertEquals(1, source.index, "the left page of a right-to-left pair is the later one")
        assertEquals(1, source.page)
        assertEquals(PixelSize(3000, 4500), source.size)
        val view = assertNotNull(state.loupeView(0))
        val shown = state.geometry()!!.pages[1].height / 4500f
        assertEquals(1f / shown, view.magnification, 0.001f)
        assertNull(state.copy(config = config.copy(loupeZoom = LoupeZoom.View300)).loupeSource())
    }

    @Test
    fun aSmallPageNeedsNoSecondRead() {
        val config = spreadConfig().copy(loupeZoom = LoupeZoom.Source200)
        val small = PageShape.Known(200, 300)
        val state = readerOf(List(2) { small }, config, PORTRAIT).reduce(ReaderEvent.LoupeShown(Point(100f, 1000f)))
        assertNull(state.loupeSource())
        assertEquals(1f, state.loupeView(0)!!.magnification)
    }

    @Test
    fun theReadForAHalfIsThatHalfOfTheTurnedPage() {
        val config = ReaderConfig(
            layout = LayoutSettings(LayoutMode.SplitWide, ReadingDirection.RightToLeft, coverAlone = false),
            loupeZoom = LoupeZoom.Source100,
            rotation = Rotation.Quarter,
        )
        val state = readerOf(listOf(PageShape.Known(2000, 4000)), config, PORTRAIT).reduce(ReaderEvent.LoupeShown(Point(500f, 900f)))
        assertEquals(PageView.Half::class, state.current::class)
        val source = assertNotNull(state.loupeSource())
        assertEquals(PixelSize(2000, 2000), source.size)
        assertEquals(Rotation.Quarter, source.steps.rotation)
        assertEquals(PagePart.Whole.half(Side.Right), source.steps.part)
    }
}
