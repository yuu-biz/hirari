package io.github.yuubiz.hirari.core.ui

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import io.github.yuubiz.hirari.core.model.LONG_PRESS_MS

/**
 * A press held for [holdMs]: [LONG_PRESS_MS] on the bars, `loupeHoldMs` on the turn area (docs/spec.md section 4.8).
 * Small moves within the touch slop keep coming while a finger rests, so the press is due by the clock of the events
 * as well as by a timeout when none comes.
 */
class HoldTimer(downAtMs: Long, holdMs: Long = LONG_PRESS_MS) {
    val at = downAtMs + holdMs
    var lastMs = downAtMs

    val due: Boolean get() = lastMs >= at

    fun seen(event: PointerEvent) {
        lastMs = maxOf(lastMs, event.changes.maxOf { it.uptimeMillis })
    }
}

/** The next event, or `null` when the press of [hold] became due first. Only a [waiting] press times out. */
suspend fun AwaitPointerEventScope.awaitOrHold(hold: HoldTimer, waiting: Boolean): PointerEvent? {
    if (!waiting || hold.due) return awaitPointerEvent().also(hold::seen)
    val event = withTimeoutOrNull((hold.at - hold.lastMs).coerceAtLeast(1)) { awaitPointerEvent() }
    if (event == null) hold.lastMs = hold.at else hold.seen(event)
    return event
}
