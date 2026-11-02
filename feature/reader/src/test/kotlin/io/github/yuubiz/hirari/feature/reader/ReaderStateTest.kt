package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.TurnEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val TALL = PageShape.Known(1000, 1500)
internal val WIDE = PageShape.Known(2000, 1500)
internal val PORTRAIT = Viewport(1080f, 1920f, density = 3f)
internal val LANDSCAPE = Viewport(1920f, 1080f, density = 3f)

internal fun spreadConfig(direction: ReadingDirection = ReadingDirection.RightToLeft, coverAlone: Boolean = false) =
    ReaderConfig(layout = LayoutSettings(LayoutMode.Spread, direction, coverAlone))

/** A book whose shapes are all known, as after prefetch read them. */
internal fun readerOf(
    shapes: List<PageShape.Known>,
    config: ReaderConfig = spreadConfig(),
    viewport: Viewport = LANDSCAPE,
    start: Int = 0,
): ReaderState = shapes.foldIndexed(ReaderState.open(shapes.size, start, config, viewport)) { page, state, shape ->
    state.reduce(ReaderEvent.ShapeLearned(page, shape))
}

private fun ReaderState.act(action: ReaderAction, nowMs: Long = 0) = reduce(ReaderEvent.Act(action, nowMs))

class ReaderStateTest {
    @Test
    fun aTurnMovesToTheNextViewAndAnimatesFromTheOldOne() {
        val state = readerOf(List(4) { TALL })
        val turned = state.act(ReaderAction.TurnView(Turn.Forward), nowMs = 100)
        assertEquals(PageView.Two(2, 3), turned.current)
        val transition = assertNotNull(turned.transition)
        assertEquals(PageView.Two(0, 1), transition.outgoing)
        assertEquals(PageView.Two(2, 3), transition.incoming)
        assertNull(turned.reduce(ReaderEvent.Tick(100 + state.config.turnDurationMs)).transition)
    }

    @Test
    fun aTurnPastTheEndChangesNothing() {
        val state = readerOf(List(2) { TALL })
        assertEquals(state, state.act(ReaderAction.TurnView(Turn.Forward)))
    }

    @Test
    fun theNextInputEndsTheRunningTurnAtOnce() {
        val state = readerOf(List(6) { TALL }).act(ReaderAction.TurnView(Turn.Forward), nowMs = 0)
        val again = state.act(ReaderAction.TurnView(Turn.Forward), nowMs = 10)
        assertEquals(PageView.Two(4, 5), again.current)
        assertEquals(PageView.Two(2, 3), again.transition!!.outgoing)
    }

    @Test
    fun turningBackEntersAtTheLastStop() {
        val config = spreadConfig().copy(scaleMode = ScaleMode.FitHeight)
        val state = readerOf(List(4) { TALL }, config, PORTRAIT, start = 2)
        val back = state.act(ReaderAction.TurnView(Turn.Back))
        val path = back.geometry()!!.stops(back.config)
        assertTrue(path.last > 0)
        assertEquals(path.last, back.stopIndex())
    }

    @Test
    fun stepsWalkTheStopsBeforeTheyTurn() {
        val config = ReaderConfig(layout = LayoutSettings(LayoutMode.Single, ReadingDirection.RightToLeft, false), scaleMode = ScaleMode.FitWidth)
        var state = readerOf(List(2) { TALL }, config, LANDSCAPE)
        val stops = state.geometry()!!.stops(config).stops.size
        assertTrue(stops > 1)
        repeat(stops - 1) {
            state = state.act(ReaderAction.StepStops(Turn.Forward))
            assertEquals(PageView.One(0), state.current)
        }
        assertEquals(stops - 1, state.stopIndex())
        state = state.act(ReaderAction.StepStops(Turn.Forward))
        assertEquals(PageView.One(1), state.current)
        assertEquals(0, state.stopIndex())
    }

    @Test
    fun aStepAnimatesAtTheStopSpeedAndAnInputLandsItAtOnce() {
        val config = ReaderConfig(scaleMode = ScaleMode.FitWidth)
        val state = readerOf(List(1) { TALL }, config, LANDSCAPE)
        val first = state.positionAt(0)!!
        val stepping = state.act(ReaderAction.StepStops(Turn.Forward), nowMs = 0)
        val travel = assertNotNull(stepping.stopTravel)
        assertEquals(first, stepping.positionAt(0))
        val target = stepping.geometry()!!.stops(config).stops[1].point
        assertEquals(target, stepping.positionAt(travel.move.motion.durationMs))
        val landed = stepping.reduce(ReaderEvent.Pan(0f, 0f))
        assertNull(landed.stopTravel)
        assertEquals(target, landed.positionAt(0))
    }

    @Test
    fun aForwardStepAfterAPanGoesToTheNearestStopPastTheScreen() {
        val config = ReaderConfig(scaleMode = ScaleMode.FitWidth)
        val state = readerOf(List(1) { TALL }, config, LANDSCAPE)
        val stops = state.geometry()!!.stops(config).stops
        val between = (stops[1].point.y + stops[2].point.y) / 2
        val panned = state.reduce(ReaderEvent.Pan(0f, -between))
        assertEquals(Place.Panned(Point(stops[0].point.x, between)), panned.place)
        assertEquals(Place.AtStop(2), panned.act(ReaderAction.StepStops(Turn.Forward)).place)
        assertEquals(Place.AtStop(1), panned.act(ReaderAction.StepStops(Turn.Back)).place)
    }

    @Test
    fun aViewThatFitsDoesNotPan() {
        val state = readerOf(List(2) { TALL })
        assertEquals(state.place, state.reduce(ReaderEvent.Pan(100f, 100f)).place)
    }

    @Test
    fun aShiftReanchorsThePairsWithoutAnEffect() {
        val state = readerOf(List(5) { TALL })
        val shifted = state.act(ReaderAction.Shift(forward = true))
        assertEquals(PageView.Two(1, 2), shifted.current)
        assertEquals(listOf(PageView.One(0), PageView.Two(1, 2), PageView.Two(3, 4)), shifted.pagination.views)
        assertNull(shifted.transition)
    }

    @Test
    fun aJumpGoesToTheViewThatStartsAtThePage() {
        val state = readerOf(List(5) { TALL }).reduce(ReaderEvent.GoTo(3))
        assertEquals(PageView.Two(3, 4), state.current)
        assertNull(state.transition)
    }

    @Test
    fun aPullThatCommitsTurnsFromWhereTheFingerLeft() {
        val config = spreadConfig().copy(turnEffect = TurnEffect.Slide)
        val state = readerOf(List(4) { TALL }, config)
        val pulling = state.reduce(ReaderEvent.Pulling(Turn.Forward, pulledDp = 80f))
        assertEquals(PageView.Two(2, 3), pulling.pull!!.neighbor)
        val turned = pulling.reduce(ReaderEvent.PullEnded(commit = true, nowMs = 0))
        assertEquals(PageView.Two(2, 3), turned.current)
        val motion = turned.transition!!.animation.motion
        assertEquals(80f * LANDSCAPE.density / LANDSCAPE.width, motion.from, 1e-4f)
        assertTrue(motion.durationMs < config.turnDurationMs)
    }

    @Test
    fun aPullThatDoesNotCommitGoesBackToRest() {
        val state = readerOf(List(4) { TALL })
        val back = state.reduce(ReaderEvent.Pulling(Turn.Forward, 30f)).reduce(ReaderEvent.PullEnded(commit = false, nowMs = 0))
        assertEquals(PageView.Two(0, 1), back.current)
        assertEquals(PageView.Two(2, 3), back.transition!!.incoming)
        assertNull(back.reduce(ReaderEvent.Tick(PULL_RETURN_MS)).transition)
    }

    @Test
    fun aShapeLearnedAheadLeavesTheCurrentViewAlone() {
        val start = readerOf(List(3) { TALL })
        val learned = start.reduce(ReaderEvent.ShapeLearned(2, WIDE))
        assertEquals(PageView.Two(0, 1), learned.current)
        assertEquals(listOf(PageView.Two(0, 1), PageView.One(2)), learned.pagination.views)
    }

    @Test
    fun aPinchKeepsThePointUnderTheFingers() {
        val state = readerOf(List(1) { TALL }, ReaderConfig(), PORTRAIT)
        val focus = Point(300f, 500f)
        val before = state.geometry()!!
        val start = state.positionAt(0)!!
        val zoomed = state
            .reduce(ReaderEvent.Zoom(ZoomEvent.FingersDown(100f), focus))
            .reduce(ReaderEvent.Zoom(ZoomEvent.SpanChanged(130f), focus))
            .reduce(ReaderEvent.Zoom(ZoomEvent.SpanChanged(200f), focus))
        val after = zoomed.geometry()!!
        assertTrue(zoomed.zoom.factor > 1f)
        val position = zoomed.positionAt(0)!!
        val ratio = after.size.width / before.size.width
        assertEquals((focus.x + start.x) * ratio, focus.x + position.x, 1f)
        assertEquals((focus.y + start.y) * ratio, focus.y + position.y, 1f)
    }

    @Test
    fun theZoomStaysAcrossTurnsAndResetsWithTheScaleMode() {
        val state = readerOf(List(4) { TALL }, ReaderConfig(), PORTRAIT)
            .reduce(ReaderEvent.Zoom(ZoomEvent.FingersDown(100f)))
            .reduce(ReaderEvent.Zoom(ZoomEvent.SpanChanged(130f)))
            .reduce(ReaderEvent.Zoom(ZoomEvent.SpanChanged(200f)))
            .reduce(ReaderEvent.Zoom(ZoomEvent.FingersUp))
        val factor = state.zoom.factor
        assertTrue(factor > 1f)
        assertEquals(factor, state.act(ReaderAction.TurnView(Turn.Forward)).zoom.factor)
        val modeChanged = state.reduce(ReaderEvent.ConfigChanged(state.config.copy(scaleMode = ScaleMode.FitWidth)))
        assertEquals(1f, modeChanged.zoom.factor)
    }

    @Test
    fun turningTheScreenRelaysFromTheHeadOfTheView() {
        val config = ReaderConfig(layout = LayoutSettings(LayoutMode.Auto, ReadingDirection.RightToLeft, coverAlone = false))
        val state = readerOf(listOf(TALL, TALL, TALL, WIDE), config, LANDSCAPE, start = 2)
        assertEquals(PageView.One(2), state.current)
        val portrait = state.reduce(ReaderEvent.ViewportChanged(PORTRAIT))
        assertEquals(PageView.One(2), portrait.current)
        assertEquals(4 + 1, portrait.pagination.views.size)
    }
}
