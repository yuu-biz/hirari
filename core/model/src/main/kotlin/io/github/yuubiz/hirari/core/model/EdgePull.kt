package io.github.yuubiz.hirari.core.model

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.tanh

const val PULL_MAX_DP = 150f
const val PULL_COMMIT_DP = PULL_MAX_DP * 0.9f
const val PULL_FLING_MIN_DP = 24f
const val PULL_FLING_DP_PER_S = 1000f

private const val PULL_KNEE_DP = PULL_MAX_DP / 2f

/**
 * The pull shown for a finger travel of [travel] dp: one to one up to half the cap, then easing
 * toward the cap so the pull moves less than the finger and never reaches 150 dp.
 */
fun resistedPull(travel: Float): Float = when {
    travel <= 0f -> 0f
    travel <= PULL_KNEE_DP -> travel
    else -> PULL_KNEE_DP + PULL_KNEE_DP * tanh((travel - PULL_KNEE_DP) / PULL_KNEE_DP)
}

/**
 * The sides a finger may pull toward, from how far the view reaches past the left and right
 * edges of the screen when the finger goes down. A finger moving right shows what lies left, so it
 * pulls only when nothing is hidden on the left.
 */
fun pullableSides(hiddenLeft: Float, hiddenRight: Float): Set<Side> = buildSet {
    if (hiddenLeft <= 0f) add(Side.Right)
    if (hiddenRight <= 0f) add(Side.Left)
}

/** The turn for a finger pulling toward [side]: right goes forward in a right-to-left book unless [mirrored]. */
fun pullTurn(side: Side, direction: ReadingDirection, mirrored: Boolean): Turn {
    val forwardSide = if (direction == ReadingDirection.RightToLeft) Side.Right else Side.Left
    return if ((side == forwardSide) != mirrored) Turn.Forward else Turn.Back
}

/**
 * One finger on the turn area (docs/spec.md section 4.8, タップ領域, 端引きでめくる, and ルーペ). Every
 * length is in dp. [swipeToTurn] off makes every drag a scroll. A finger held still for [holdMs], the
 * `loupeHoldMs` setting, brings up the loupe; a release before then is a tap.
 */
data class TurnAreaTouch(
    val direction: ReadingDirection,
    val mirrored: Boolean,
    val swipeToTurn: Boolean,
    val touchSlop: Float,
    val holdMs: Long,
) {
    fun reduce(state: TouchState, event: TouchEvent): TouchStep = when (event) {
        is TouchEvent.Down -> TouchStep(TouchState.Pressed(event.point, event.timeMs, event.pullable, event.systemEdges))
        is TouchEvent.Hold ->
            if (state is TouchState.Pressed && event.timeMs - state.downAtMs >= holdMs) {
                TouchStep(TouchState.Loupe(state.origin), TouchOutcome.ShowLoupe(state.origin))
            } else {
                TouchStep(state)
            }
        is TouchEvent.Move -> TouchStep(moved(state, event.point))
        is TouchEvent.Up -> when (val last = moved(state, event.point)) {
            is TouchState.Pressed ->
                TouchStep(TouchState.Idle, if (event.timeMs - last.downAtMs < holdMs) TouchOutcome.Tap(last.origin) else null)
            is TouchState.Pulling -> TouchStep(TouchState.Idle, release(last, event.velocityX))
            is TouchState.Loupe -> TouchStep(TouchState.Idle, TouchOutcome.HideLoupe)
            TouchState.Idle, TouchState.Scrolling, TouchState.System -> TouchStep(TouchState.Idle)
        }
        TouchEvent.Cancel -> TouchStep(
            TouchState.Idle,
            when (state) {
                is TouchState.Pulling -> TouchOutcome.Return(state.turn, state.pulled)
                is TouchState.Loupe -> TouchOutcome.HideLoupe
                else -> null
            },
        )
    }

    private fun moved(state: TouchState, point: Point): TouchState = when (state) {
        is TouchState.Pressed -> {
            val dx = point.x - state.origin.x
            val dy = point.y - state.origin.y
            val side = if (dx > 0f) Side.Right else Side.Left
            when {
                hypot(dx, dy) <= touchSlop -> state
                state.systemEdges.takeSwipe(state.origin, point) -> TouchState.System
                swipeToTurn && abs(dx) > abs(dy) && side in state.pullable ->
                    TouchState.Pulling(state.origin.x, side, pullTurn(side, direction, mirrored), abs(dx))
                else -> TouchState.Scrolling
            }
        }
        is TouchState.Pulling -> {
            val dx = point.x - state.originX
            state.copy(travel = if (state.side == Side.Right) dx else -dx)
        }
        is TouchState.Loupe -> TouchState.Loupe(point)
        TouchState.Idle, TouchState.Scrolling, TouchState.System -> state
    }

    private fun release(pull: TouchState.Pulling, velocityX: Float): TouchOutcome {
        val toward = if (pull.side == Side.Right) velocityX else -velocityX
        val turns = pull.pulled >= PULL_COMMIT_DP || pull.pulled >= PULL_FLING_MIN_DP && toward >= PULL_FLING_DP_PER_S
        return if (turns) TouchOutcome.Commit(pull.turn, pull.pulled) else TouchOutcome.Return(pull.turn, pull.pulled)
    }
}

sealed interface TouchState {
    data object Idle : TouchState

    /**
     * [pullable] is fixed when the finger goes down, so a scroll that reaches an edge never turns. [systemEdges] are
     * the edges whose system gesture bands the finger went down in.
     */
    data class Pressed(val origin: Point, val downAtMs: Long, val pullable: Set<Side>, val systemEdges: Set<ScreenEdge> = emptySet()) : TouchState

    /** [travel] is how far the finger has gone toward [side]. It may be negative after turning around. */
    data class Pulling(val originX: Float, val side: Side, val turn: Turn, val travel: Float) : TouchState {
        val pulled: Float get() = resistedPull(travel)
    }

    /** The drag pans the view, or the pull is not allowed. */
    data object Scrolling : TouchState

    /** A swipe into the screen from a system gesture band: the system's, so it neither turns nor scrolls. */
    data object System : TouchState

    /** The loupe is up and follows the finger at [finger]; the touch neither turns nor scrolls. */
    data class Loupe(val finger: Point) : TouchState
}

sealed interface TouchEvent {
    /** [pullable] comes from [pullableSides], and [systemEdges] from [ReaderRegions.systemEdgesAt]. */
    data class Down(val point: Point, val timeMs: Long, val pullable: Set<Side>, val systemEdges: Set<ScreenEdge> = emptySet()) : TouchEvent
    data class Move(val point: Point) : TouchEvent

    /** The finger is still down at [timeMs], sent once the hold of [TurnAreaTouch.holdMs] is due. */
    data class Hold(val timeMs: Long) : TouchEvent

    /** [velocityX] is in dp per second, positive to the right. */
    data class Up(val point: Point, val timeMs: Long, val velocityX: Float) : TouchEvent

    /** A second finger or the system took the touch. */
    data object Cancel : TouchEvent
}

sealed interface TouchOutcome {
    /** A tap at [point]. [TapZones] decides what it does. */
    data class Tap(val point: Point) : TouchOutcome

    /** Make [turn], continuing from a pull of [pulled] dp. */
    data class Commit(val turn: Turn, val pulled: Float) : TouchOutcome

    /** Go back to rest from a pull of [pulled] dp toward [turn], in about [PULL_RETURN_MS]. */
    data class Return(val turn: Turn, val pulled: Float) : TouchOutcome

    /** A hold at [point] brings up the loupe. */
    data class ShowLoupe(val point: Point) : TouchOutcome

    /** The finger of the loupe went up. */
    data object HideLoupe : TouchOutcome
}

data class TouchStep(val state: TouchState, val outcome: TouchOutcome? = null)
