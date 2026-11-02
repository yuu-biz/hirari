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
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.Size
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
    private val haptic: () -> Unit,
    private val onClose: () -> Unit,
) {
    suspend fun PointerInputScope.handle() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val current = state()
            val density = current.viewport.density
            val point = down.position.dp(density)
            val regions = ReaderRegions(Size(size.width / density, size.height / density), current.config.barHeightDp, systemBarsHidden = true)
            val slop = viewConfiguration.touchSlop / density
            if (ui.commandBar != CommandBarState.Closed) {
                commandBar(down, current, slop)
                return@awaitEachGesture
            }
            when (regions.regionAt(point)) {
                ReaderRegion.CommandBar -> commandBar(down, current, slop)
                ReaderRegion.PageBar -> pageBar(down, current, regions, slop)
                ReaderRegion.TurnArea -> turnArea(down, current, regions, slop, viewConfiguration.longPressTimeoutMillis)
                null -> Unit
            }
        }
    }

    private suspend fun AwaitPointerEventScope.commandBar(down: PointerInputChange, current: ReaderState, slop: Float) {
        val panel = current.commandPanel()
        val density = current.viewport.density
        fun step(event: CommandBarEvent) {
            val step = panel.reduce(ui.commandBar, event, slop)
            ui.commandBar = step.state
            when (val effect = step.effect) {
                CommandBarEffect.Haptic -> haptic()
                is CommandBarEffect.Execute -> execute(effect.item)
                null -> Unit
            }
        }
        step(CommandBarEvent.Down(down.position.dp(density), down.uptimeMillis, systemBarsHidden = true))
        down.consume()
        if (ui.commandBar is CommandBarState.Holding) {
            val early = withTimeoutOrNull(LONG_PRESS_MS) { awaitPointerEvent() }
            if (early == null) step(CommandBarEvent.Hold(down.uptimeMillis + LONG_PRESS_MS)) else if (finishCommand(early, down.id, density, ::step)) return
        }
        while (true) {
            if (finishCommand(awaitPointerEvent(), down.id, density, ::step)) return
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

    private fun execute(item: PanelItem) {
        val now = SystemClock.uptimeMillis()
        when (item) {
            PanelItem.Close -> onClose()
            PanelItem.Menu -> ui.message = R.string.reader_not_yet
            is PanelItem.Command -> when {
                item.command == ReaderCommand.Orientation -> viewModel.nextOrientation()
                !viewModel.command(item.command, now) -> ui.message = R.string.reader_not_yet
            }
        }
    }

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
                PageBarEffect.OpenPagePanel -> ui.message = R.string.reader_not_yet
                null -> Unit
            }
        }
        step(PageBarEvent.Down(down.position.dp(density), down.uptimeMillis, current.current.pages.first()))
        down.consume()
        val early = withTimeoutOrNull(LONG_PRESS_MS) { awaitPointerEvent() }
        if (early == null) {
            step(PageBarEvent.Hold(down.uptimeMillis + LONG_PRESS_MS))
            if (ui.pageBar == PageBarState.Idle) return
        }
        var event = early ?: awaitPointerEvent()
        while (true) {
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            change.consume()
            if (!change.pressed) {
                step(PageBarEvent.Up(change.position.dp(density)))
                return
            }
            step(PageBarEvent.Move(change.position.dp(density)))
            event = awaitPointerEvent()
        }
    }

    private suspend fun AwaitPointerEventScope.turnArea(
        down: PointerInputChange,
        current: ReaderState,
        regions: ReaderRegions,
        slop: Float,
        longPressMs: Long,
    ) {
        val density = current.viewport.density
        val config = current.config
        val touch = TurnAreaTouch(config.direction, config.mirrored, config.swipeToTurn, slop, longPressMs)
        val geometry = current.geometry()
        val position = current.positionAt(SystemClock.uptimeMillis())
        val (hiddenLeft, hiddenRight) = if (geometry != null && position != null) geometry.hidden(position) else Pair(0f, 0f)
        var touchState = touch.reduce(TouchState.Idle, TouchEvent.Down(down.position.dp(density), down.uptimeMillis, pullableSides(hiddenLeft, hiddenRight))).state
        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var last = down.position
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                if (touchState is TouchState.Pulling) viewModel.dispatch(ReaderEvent.PullEnded(commit = false, SystemClock.uptimeMillis()))
                pinch(event, down.id, density, slop)
                return
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            change.consume()
            velocity.addPosition(change.uptimeMillis, change.position)
            if (!change.pressed) {
                val vx = velocity.calculateVelocity().x / density
                when (val outcome = touch.reduce(touchState, TouchEvent.Up(change.position.dp(density), change.uptimeMillis, vx)).outcome) {
                    is TouchOutcome.Tap -> tap(outcome.point, regions)
                    is TouchOutcome.Commit -> viewModel.dispatch(ReaderEvent.PullEnded(commit = true, SystemClock.uptimeMillis()))
                    is TouchOutcome.Return -> viewModel.dispatch(ReaderEvent.PullEnded(commit = false, SystemClock.uptimeMillis()))
                    null -> Unit
                }
                return
            }
            touchState = touch.reduce(touchState, TouchEvent.Move(change.position.dp(density))).state
            when (val now = touchState) {
                is TouchState.Pulling -> viewModel.dispatch(ReaderEvent.Pulling(now.turn, now.pulled))
                TouchState.Scrolling -> viewModel.dispatch(ReaderEvent.Pan(change.position.x - last.x, change.position.y - last.y))
                TouchState.Idle, is TouchState.Pressed -> Unit
            }
            last = change.position
        }
    }

    private fun tap(point: Point, regions: ReaderRegions) {
        val current = state()
        val area = regions.turnArea
        val turn = current.config.tapZones.turnAt(Point(point.x - area.left, point.y - area.top), Size(area.width, area.height)) ?: return
        val overflows = current.geometry()?.overflows == true
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
