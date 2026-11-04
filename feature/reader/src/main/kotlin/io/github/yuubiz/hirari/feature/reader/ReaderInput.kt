package io.github.yuubiz.hirari.feature.reader

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import io.github.yuubiz.hirari.core.model.CommandBarEffect
import io.github.yuubiz.hirari.core.model.CommandBarEvent
import io.github.yuubiz.hirari.core.model.CommandBarState
import io.github.yuubiz.hirari.core.model.PageBar
import io.github.yuubiz.hirari.core.model.PageBarEffect
import io.github.yuubiz.hirari.core.model.PageBarEvent
import io.github.yuubiz.hirari.core.model.PageBarState
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderRegion
import io.github.yuubiz.hirari.core.model.ReaderRegions
import io.github.yuubiz.hirari.core.model.ScrollMotion
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.SystemGestureEdges
import io.github.yuubiz.hirari.core.model.TouchEvent
import io.github.yuubiz.hirari.core.model.TouchOutcome
import io.github.yuubiz.hirari.core.model.TouchState
import io.github.yuubiz.hirari.core.model.TurnAreaTouch
import io.github.yuubiz.hirari.core.model.pullableSides
import io.github.yuubiz.hirari.core.model.reduce
import io.github.yuubiz.hirari.core.model.tapAction
import io.github.yuubiz.hirari.core.ui.HoldTimer
import io.github.yuubiz.hirari.core.ui.awaitOrHold
import kotlin.math.hypot

/** A second finger that goes up within this time, without moving past the touch slop, taps. */
private const val SECOND_FINGER_TAP_MS = 300L

/**
 * Turns pointer input into reader events (docs/spec.md section 4.8): the command bar, the page bar, and taps,
 * pulls, pans, and pinches on the turn area. The reducers of those parts decide; this only feeds them.
 */
internal class ReaderInput(
    private val viewModel: ReaderViewModel,
    private val ui: ReaderUi,
    private val state: () -> ReaderState,
    /** Where system gestures start on this screen. */
    private val edges: () -> SystemGestureEdges,
    private val haptic: () -> Unit,
    /** The feedback of a hold that brings up the loupe. */
    private val holdHaptic: () -> Unit,
    private val onClose: () -> Unit,
) {
    suspend fun PointerInputScope.handle() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val current = state()
            val density = current.viewport.density
            val point = down.position.dp(density)
            val regions = ReaderRegions(Size(size.width / density, size.height / density), current.config.barHeightDp, edges())
            val slop = viewConfiguration.touchSlop / density
            if (current.autoPlay != null) {
                viewModel.dispatch(ReaderEvent.AutoPlayStopped)
                swallow(down)
                return@awaitEachGesture
            }
            if (ui.commandBar != CommandBarState.Closed) {
                commandBar(down, current, slop)
                return@awaitEachGesture
            }
            when (regions.regionAt(point)) {
                ReaderRegion.CommandBar -> commandBar(down, current, slop)
                ReaderRegion.PageBar -> pageBar(down, current, regions, slop)
                ReaderRegion.TurnArea -> turnArea(down, current, regions, slop)
            }
        }
    }

    /** A touch that only stopped 自動再生: every finger of it is taken until the last one is up. */
    private suspend fun AwaitPointerEventScope.swallow(down: PointerInputChange) {
        down.consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }

    private suspend fun AwaitPointerEventScope.commandBar(down: PointerInputChange, current: ReaderState, slop: Float) {
        val panel = current.commandPanel()
        val density = current.viewport.density
        fun step(event: CommandBarEvent) {
            val step = panel.reduce(ui.commandBar, event, slop)
            ui.commandBar = step.state
            when (val effect = step.effect) {
                CommandBarEffect.Haptic -> haptic()
                is CommandBarEffect.Execute -> (effect.item as? PanelItem)?.let(::execute)
                null -> Unit
            }
        }
        step(CommandBarEvent.Down(down.position.dp(density), down.uptimeMillis, systemBarsHidden = true))
        down.consume()
        val hold = HoldTimer(down.uptimeMillis)
        while (true) {
            val holding = ui.commandBar is CommandBarState.Holding
            val event = awaitOrHold(hold, waiting = holding)
            if (holding && hold.due) step(CommandBarEvent.Hold(hold.at))
            if (event != null && finishCommand(event, down.id, density, ::step)) return
        }
    }

    /** Feeds one event to the command bar; true once the finger is up. */
    private fun finishCommand(event: PointerEvent, id: PointerId, density: Float, step: (CommandBarEvent) -> Unit): Boolean {
        val change = event.changes.firstOrNull { it.id == id } ?: event.changes.first()
        change.consume()
        return if (change.pressed) {
            step(CommandBarEvent.Move(change.position.dp(density)))
            false
        } else {
            step(CommandBarEvent.Up(change.position.dp(density)))
            true
        }
    }

    private fun execute(item: PanelItem) = runPanelItem(item, viewModel, ui, onClose)

    private suspend fun AwaitPointerEventScope.pageBar(down: PointerInputChange, current: ReaderState, regions: ReaderRegions, slop: Float) {
        val density = current.viewport.density
        val bar = PageBar(
            left = 0f,
            right = regions.screen.width,
            top = regions.screen.height - regions.barHeight,
            height = regions.barHeight,
            pageCount = current.pagination.shapes.size,
            direction = current.config.direction,
            touchSlop = slop,
        )
        fun step(event: PageBarEvent) {
            val step = bar.reduce(ui.pageBar, event)
            ui.pageBar = step.state
            when (val effect = step.effect) {
                is PageBarEffect.GoTo -> viewModel.dispatch(ReaderEvent.GoTo(effect.page))
                PageBarEffect.OpenPagePanel -> ui.sheet = ReaderSheet.Pages(openedOn = state().current.pages.min())
                null -> Unit
            }
        }
        val point = down.position.dp(density)
        step(PageBarEvent.Down(point, down.uptimeMillis, current.current.pages.first(), regions.systemEdgesAt(point)))
        down.consume()
        val hold = HoldTimer(down.uptimeMillis)
        var held = false
        while (true) {
            val event = awaitOrHold(hold, waiting = !held)
            if (!held && hold.due) {
                held = true
                step(PageBarEvent.Hold(hold.at))
                if (ui.pageBar == PageBarState.Idle) return
            }
            if (event == null) continue
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            change.consume()
            if (!change.pressed) {
                step(PageBarEvent.Up(change.position.dp(density)))
                return
            }
            step(PageBarEvent.Move(change.position.dp(density)))
            if (ui.pageBar == PageBarState.Idle) return
        }
    }

    private suspend fun AwaitPointerEventScope.turnArea(
        down: PointerInputChange,
        current: ReaderState,
        regions: ReaderRegions,
        slop: Float,
    ) {
        val density = current.viewport.density
        val config = current.config
        val touch = TurnAreaTouch(config.direction, config.mirrored, config.swipeToTurn, slop, config.loupeHoldMs)
        val geometry = current.geometry()
        val position = current.positionAt(SystemClock.uptimeMillis())
        val (hiddenLeft, hiddenRight) = if (geometry != null && position != null) geometry.hidden(position) else Pair(0f, 0f)
        val pullable = if (current.scroll != null) emptySet() else pullableSides(hiddenLeft, hiddenRight)
        val caught = current.scroll?.motion is ScrollMotion.Glide || current.place is Place.Gliding
        if (caught) viewModel.dispatch(ReaderEvent.Catch)
        val point = down.position.dp(density)
        var touchState = touch.reduce(TouchState.Idle, TouchEvent.Down(point, down.uptimeMillis, pullable, regions.systemEdgesAt(point))).state
        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var last = down.position
        val hold = HoldTimer(down.uptimeMillis, config.loupeHoldMs)
        try {
            while (true) {
                val pressed = touchState is TouchState.Pressed
                val event = awaitOrHold(hold, waiting = pressed)
                if (pressed && hold.due) {
                    val step = touch.reduce(touchState, TouchEvent.Hold(hold.at))
                    touchState = step.state
                    if (step.outcome is TouchOutcome.ShowLoupe) {
                        holdHaptic()
                        viewModel.dispatch(ReaderEvent.LoupeShown(Point(down.position.x, down.position.y)))
                    }
                }
                event ?: continue
                if (followed(event, down, touch, touchState, regions, velocity, density, slop, caught)) return
                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                touchState = touch.reduce(touchState, TouchEvent.Move(change.position.dp(density))).state
                when (val now = touchState) {
                    is TouchState.Pulling -> viewModel.dispatch(ReaderEvent.Pulling(now.turn, now.pulled))
                    TouchState.Scrolling -> viewModel.dispatch(ReaderEvent.Pan(change.position.x - last.x, change.position.y - last.y))
                    is TouchState.Loupe -> viewModel.dispatch(ReaderEvent.LoupeMoved(Point(change.position.x, change.position.y), change.uptimeMillis))
                    TouchState.Idle, TouchState.System, is TouchState.Pressed -> Unit
                }
                last = change.position
            }
        } finally {
            if (touchState is TouchState.Loupe) viewModel.dispatch(ReaderEvent.LoupeHidden)
        }
    }

    /**
     * Feeds [event] to the touch in [state]; true once the touch is over, by a finger going up or a second one
     * starting a pinch. While the loupe is up a second finger changes nothing. A touch that [caught] a glide does not
     * tap, and a scroll let go while moving glides on.
     */
    private suspend fun AwaitPointerEventScope.followed(
        event: PointerEvent,
        down: PointerInputChange,
        touch: TurnAreaTouch,
        state: TouchState,
        regions: ReaderRegions,
        velocity: VelocityTracker,
        density: Float,
        slop: Float,
        caught: Boolean,
    ): Boolean {
        if (state !is TouchState.Loupe && event.changes.count { it.pressed } >= 2) {
            if (state is TouchState.Pulling) viewModel.dispatch(ReaderEvent.PullEnded(commit = false, SystemClock.uptimeMillis()))
            pinch(event, down.id, density, slop)
            return true
        }
        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
        event.changes.forEach { it.consume() }
        velocity.addPosition(change.uptimeMillis, change.position)
        if (change.pressed) return false
        val released = velocity.calculateVelocity()
        val vx = released.x / density
        if (state is TouchState.Scrolling) viewModel.dispatch(ReaderEvent.Fling(released.x, released.y, change.uptimeMillis))
        when (val outcome = touch.reduce(state, TouchEvent.Up(change.position.dp(density), change.uptimeMillis, vx)).outcome) {
            is TouchOutcome.Tap -> if (!caught) tap(outcome.point, regions, change.uptimeMillis)
            is TouchOutcome.Commit -> viewModel.dispatch(ReaderEvent.PullEnded(commit = true, SystemClock.uptimeMillis()))
            is TouchOutcome.Return -> viewModel.dispatch(ReaderEvent.PullEnded(commit = false, SystemClock.uptimeMillis()))
            TouchOutcome.HideLoupe -> viewModel.dispatch(ReaderEvent.LoupeHidden)
            is TouchOutcome.ShowLoupe, null -> Unit
        }
        return true
    }

    private fun tap(point: Point, regions: ReaderRegions, eventUptimeMillis: Long) {
        val current = state()
        val area = regions.turnArea
        val turn = current.config.tapZones.turnAt(Point(point.x - area.left, point.y - area.top), Size(area.width, area.height)) ?: return
        val overflows = current.geometry()?.overflows == true
        viewModel.traces.input(eventUptimeMillis)
        viewModel.act(tapAction(turn, overflows, current.config.tapAdvancesStops), SystemClock.uptimeMillis())
    }

    /** Two fingers: the pinch of [ZoomState], and taps of the second finger that reset it. */
    private suspend fun AwaitPointerEventScope.pinch(first: PointerEvent, firstId: PointerId, density: Float, slop: Float) {
        var event = first
        var fingersDown = false
        var second: PointerInputChange? = event.changes.firstOrNull { it.id != firstId && it.pressed }
        var secondDownAt = second?.uptimeMillis ?: 0L
        var secondFrom = second?.position ?: Offset.Zero
        while (true) {
            for (change in event.changes) {
                if (change.id == firstId) continue
                if (change.changedToDown()) {
                    second = change
                    secondDownAt = change.uptimeMillis
                    secondFrom = change.position
                }
                if (change.changedToUp() && change.id == second?.id &&
                    change.uptimeMillis - secondDownAt <= SECOND_FINGER_TAP_MS && (change.position - secondFrom).getDistance() <= slop * density
                ) {
                    viewModel.dispatch(ReaderEvent.Zoom(ZoomEvent.SecondFingerTap(change.uptimeMillis)))
                }
            }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val a = pressed[0].position
                val b = pressed[1].position
                val span = hypot(a.x - b.x, a.y - b.y) / density
                val focus = Point((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                val zoom = if (fingersDown) ZoomEvent.SpanChanged(span) else ZoomEvent.FingersDown(span)
                viewModel.dispatch(ReaderEvent.Zoom(zoom, focus))
                fingersDown = true
            } else {
                fingersDown = false
            }
            event.changes.forEach { it.consume() }
            if (pressed.isEmpty()) {
                viewModel.dispatch(ReaderEvent.Zoom(ZoomEvent.FingersUp))
                return
            }
            event = awaitPointerEvent()
        }
    }
}

private fun Offset.dp(density: Float) = Point(x / density, y / density)
