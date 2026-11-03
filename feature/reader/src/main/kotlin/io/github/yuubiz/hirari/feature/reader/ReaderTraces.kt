package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.ui.FirstPageTrace
import io.github.yuubiz.hirari.core.ui.TraceSection

/**
 * The trace sections of docs/spec.md section 5.2 that the reader owns, on the main thread.
 *
 * - `hirari.turn lagUs=N` (P1, P2): from the app handling an input that moves to another view, whose
 *   target is already prepared, to the frame that draws it. `N` is how long the input had waited for the app
 *   since its event time, so a benchmark can measure from the event time, which a section cannot start at.
 * - `hirari.jump lagUs=N` (P3): the same for a target that was not prepared yet, which ends once its image is drawn.
 * - `hirari.firstPage` (P4): see [FirstPageTrace].
 */
internal class ReaderTraces {
    private class Pending(val view: PageView, val section: TraceSection)

    private var inputMs = 0L
    private var pending: Pending? = null
    private var firstPageDrawn = false

    /** The event time of the input that the next [moved] belongs to, in `SystemClock.uptimeMillis` terms. */
    fun input(eventUptimeMillis: Long) {
        inputMs = eventUptimeMillis
    }

    fun moved(event: ReaderEvent, before: ReaderState, after: ReaderState, targetPrepared: () -> Boolean) {
        if (before.current == after.current) return
        if (event !is ReaderEvent.Act && event !is ReaderEvent.GoTo && event !is ReaderEvent.PullEnded && event !is ReaderEvent.AutoPlayDue) return
        val lagUs = lagMicros()
        inputMs = 0L
        pending?.section?.end()
        val name = if (targetPrepared()) "hirari.turn" else "hirari.jump"
        pending = Pending(after.current, TraceSection.begin("$name lagUs=$lagUs"))
    }

    /** A frame is being recorded with [view] as the view at rest or entering; [ready] when its pages are prepared. */
    fun drawn(view: PageView, ready: Boolean) {
        if (!ready) return
        if (!firstPageDrawn) {
            firstPageDrawn = true
            FirstPageTrace.end()
        }
        val open = pending ?: return
        if (open.view == view) {
            open.section.end()
            pending = null
        }
    }

    fun close() {
        pending?.section?.end()
        pending = null
        FirstPageTrace.end()
    }

    private fun lagMicros(): Long {
        if (inputMs == 0L) return 0L
        val lag = (System.nanoTime() - inputMs * NANOS_PER_MILLI) / 1000
        return if (lag in 0..MAX_LAG_US) lag else 0L
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_LAG_US = 1_000_000L
    }
}
