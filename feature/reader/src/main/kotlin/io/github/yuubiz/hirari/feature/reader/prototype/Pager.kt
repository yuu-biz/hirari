package io.github.yuubiz.hirari.feature.reader.prototype

import android.os.Build
import android.os.Trace
import kotlin.math.abs

private const val TURN_NANOS = 150_000_000L
private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 8f
private const val COMMIT_FRACTION = 0.25f

/**
 * The page-turn state both prototypes draw: the current view, the view sliding in over it ("重ねる" in
 * docs/spec.md section 4.8, right binding), and the pinch transform. It holds no drawing code, so the two
 * surfaces differ only in how they draw and move the same state.
 */
internal class Pager(val store: PageStore, private val listener: Listener) {
    interface Listener {
        fun onContentChanged()

        fun onMotionChanged()
    }

    var current = 0
        private set

    var incoming: Int? = null
        private set

    /** Offset of the incoming view in view widths: -1 is fully left of the screen, 0 is fully over it. */
    var offset = 0f
        private set

    var scale = 1f
        private set
    var panX = 0f
        private set
    var panY = 0f
        private set

    private var animation: Animation? = null
    private var turnTrace: String? = null
    private var turnTarget = 0
    private var turnCookie = 0

    private class Animation(val from: Float, val to: Float, val startNanos: Long, val nanos: Long, val commit: Boolean)

    val animating: Boolean get() = animation != null

    fun start() = store.focus(current)

    fun close() = store.close()

    /** A tap on the left half moves forward, as the next view of a right-bound book enters from the left. */
    fun tap(x: Float, width: Float, eventUptimeMillis: Long) {
        finishAnimation()
        val forward = x < width / 2
        val target = if (forward) current + 1 else current - 1
        if (store[target] == null) {
            Trace.beginSection("hirari.turnSkipped")
            Trace.endSection()
            return
        }
        beginTurnTrace(target, eventUptimeMillis)
        incoming = target
        val from = if (forward) -1f else 1f
        animation = Animation(from, 0f, eventUptimeMillis * 1_000_000, TURN_NANOS, commit = true)
        step(System.nanoTime())
        listener.onContentChanged()
    }

    fun drag(dx: Float, width: Float) {
        finishAnimation()
        val fraction = dx / width
        if (incoming == null) {
            val target = if (fraction > 0) current + 1 else current - 1
            if (store[target] == null) return
            incoming = target
            listener.onContentChanged()
        }
        val forward = incoming!! > current
        offset = if (forward) (-1f + fraction).coerceIn(-1f, 0f) else (1f + fraction).coerceIn(0f, 1f)
        listener.onMotionChanged()
    }

    fun release(eventUptimeMillis: Long) {
        val target = incoming ?: return
        val commit = abs(offset) < 1f - COMMIT_FRACTION
        val to = if (commit) 0f else if (target > current) -1f else 1f
        val nanos = (abs(to - offset) * TURN_NANOS).toLong().coerceAtLeast(1)
        animation = Animation(offset, to, eventUptimeMillis * 1_000_000, nanos, commit)
    }

    fun cancelDrag() {
        if (animation == null && incoming != null) {
            incoming = null
            offset = 0f
            listener.onContentChanged()
        }
    }

    /** Advances the animation to the frame time; returns whether another frame is needed. */
    fun step(frameTimeNanos: Long): Boolean {
        val a = animation ?: return false
        val progress = ((frameTimeNanos - a.startNanos).toFloat() / a.nanos).coerceIn(0.02f, 1f)
        offset = a.from + (a.to - a.from) * progress
        if (progress >= 1f) {
            end(a)
            return false
        }
        listener.onMotionChanged()
        return true
    }

    fun pinch(zoom: Float, focusX: Float, focusY: Float, panDx: Float, panDy: Float, width: Float, height: Float) {
        val next = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = next / scale
        val cx = focusX - width / 2
        val cy = focusY - height / 2
        panX = cx - (cx - panX) * ratio + panDx
        panY = cy - (cy - panY) * ratio + panDy
        scale = next
        listener.onMotionChanged()
    }

    /** Called by a surface right after it records a frame that draws the view of [index]. */
    fun onDrawn(index: Int) {
        val name = turnTrace ?: return
        if (index != turnTarget) return
        if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(name, turnCookie)
        turnTrace = null
    }

    private fun finishAnimation() {
        animation?.let(::end)
    }

    private fun end(a: Animation) {
        animation = null
        offset = 0f
        if (a.commit) current = incoming ?: current
        incoming = null
        store.focus(current)
        listener.onContentChanged()
    }

    /**
     * The section starts when the app handles the input. Its name carries how long the input waited before
     * that, so the benchmark can measure from the event time, which a trace section cannot start at.
     */
    private fun beginTurnTrace(target: Int, eventUptimeMillis: Long) {
        if (Build.VERSION.SDK_INT < 29) return
        val lagMicros = (System.nanoTime() - eventUptimeMillis * 1_000_000) / 1000
        turnTarget = target
        turnCookie++
        turnTrace = "hirari.turn lagUs=$lagMicros"
        Trace.beginAsyncSection(turnTrace!!, turnCookie)
    }
}
