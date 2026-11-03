package io.github.yuubiz.hirari.core.model

import kotlin.math.hypot

/** An item a finger can land on in a command panel. */
interface PanelTarget

/** Where the command panel and its items are on screen, in dp. */
interface CommandTargets {
    fun contains(point: Point): Boolean

    /** The item under [point], or `null` outside the panel and on empty cells. */
    fun itemAt(point: Point): PanelTarget?
}

/**
 * Press, slide, and release on the command bar (docs/spec.md section 4.8, コマンドバー), which the text reader
 * shares (section 4.9). [CommandBarEvent.Down] events come only from the bar while the panel is closed, and from
 * anywhere while it is open.
 */
fun CommandTargets.reduce(state: CommandBarState, event: CommandBarEvent, touchSlop: Float): CommandBarStep = when (event) {
    is CommandBarEvent.Down -> when (state) {
        CommandBarState.Open ->
            if (contains(event.point)) slide(event.point) else CommandBarStep(CommandBarState.Closed)
        else ->
            if (event.systemBarsHidden) CommandBarStep(CommandBarState.Holding(event.point, event.timeMs)) else slide(event.point)
    }
    is CommandBarEvent.Hold ->
        if (state is CommandBarState.Holding && event.timeMs - state.downAtMs >= LONG_PRESS_MS) {
            slide(state.origin)
        } else {
            CommandBarStep(state)
        }
    is CommandBarEvent.Move -> when (state) {
        is CommandBarState.Holding ->
            CommandBarStep(if (beyond(state.origin, event.point, touchSlop)) CommandBarState.Closed else state)
        is CommandBarState.Sliding -> {
            val item = itemAt(event.point)
            val next = state.copy(highlight = item, moved = state.moved || beyond(state.origin, event.point, touchSlop))
            CommandBarStep(next, if (item != null && item != state.highlight) CommandBarEffect.Haptic else null)
        }
        CommandBarState.Closed, CommandBarState.Open -> CommandBarStep(state)
    }
    is CommandBarEvent.Up -> when (state) {
        is CommandBarState.Sliding -> {
            val item = itemAt(event.point)
            val moved = state.moved || beyond(state.origin, event.point, touchSlop)
            when {
                item != null -> CommandBarStep(CommandBarState.Closed, CommandBarEffect.Execute(item))
                moved -> CommandBarStep(CommandBarState.Closed)
                else -> CommandBarStep(CommandBarState.Open)
            }
        }
        is CommandBarState.Holding -> CommandBarStep(CommandBarState.Closed)
        CommandBarState.Closed, CommandBarState.Open -> CommandBarStep(state)
    }
    CommandBarEvent.Cancel -> CommandBarStep(CommandBarState.Closed)
}

private fun CommandTargets.slide(origin: Point): CommandBarStep {
    val item = itemAt(origin)
    return CommandBarStep(CommandBarState.Sliding(origin, item, moved = false), if (item != null) CommandBarEffect.Haptic else null)
}

private fun beyond(origin: Point, point: Point, touchSlop: Float) = hypot(point.x - origin.x, point.y - origin.y) > touchSlop

sealed interface CommandBarState {
    val panelVisible: Boolean get() = this is Sliding || this is Open

    data object Closed : CommandBarState

    /** The system bars are hidden, so the panel waits for a [LONG_PRESS_MS] press before it opens. */
    data class Holding(val origin: Point, val downAtMs: Long) : CommandBarState

    /** The panel is open under a finger, with [highlight] under it. */
    data class Sliding(val origin: Point, val highlight: PanelTarget?, val moved: Boolean) : CommandBarState

    /** A tap left the panel open. */
    data object Open : CommandBarState
}

sealed interface CommandBarEvent {
    data class Down(val point: Point, val timeMs: Long, val systemBarsHidden: Boolean) : CommandBarEvent
    data class Move(val point: Point) : CommandBarEvent
    data class Up(val point: Point) : CommandBarEvent

    /** Sent [LONG_PRESS_MS] after the press. */
    data class Hold(val timeMs: Long) : CommandBarEvent
    data object Cancel : CommandBarEvent
}

sealed interface CommandBarEffect {
    data object Haptic : CommandBarEffect
    data class Execute(val item: PanelTarget) : CommandBarEffect
}

data class CommandBarStep(val state: CommandBarState, val effect: CommandBarEffect? = null)
