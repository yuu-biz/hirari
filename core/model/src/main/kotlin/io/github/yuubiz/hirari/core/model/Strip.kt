package io.github.yuubiz.hirari.core.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/** The height over the width of a page whose shape is not known, while no page is: a portrait page of 1:√2. */
const val UNKNOWN_PAGE_ASPECT = 1.4142135623730951

/** A step of 縦に連続 moves this share of the screen's height, so the last tenth of the screen stays in view. */
const val SCROLL_STEP_SHARE = 0.9f

/** A finger let go faster than this, in dp per second, keeps the strip moving. */
const val GLIDE_MIN_DP_PER_S = 50f

/** A glide starts at this speed at most, in dp per second. */
const val GLIDE_MAX_DP_PER_S = 8000f

/** A glide ends once it is slower than this, in dp per second. */
const val GLIDE_STOP_DP_PER_S = 20f

/** A glide slows to e⁻ᵏ of its speed every second for this k: about 1/67. */
const val GLIDE_FRICTION_PER_S = 4.2

/** Positions closer than this, in pixels, count as the same. */
private const val SAME_PX = 0.5

/**
 * 縦に連続 (docs/spec.md section 2.5): the pages of a book one under another without gaps, each as wide as the
 * screen. Lengths are in page widths, so a page is 1 wide and its height over its width tall, whatever the screen.
 * A page whose shape is not known takes the mean height of the known pages, or [UNKNOWN_PAGE_ASPECT] before any is.
 */
class Strip private constructor(private val tops: DoubleArray) {
    val pageCount: Int get() = tops.size - 1

    val length: Double get() = tops[pageCount]

    fun top(page: Int): Double = tops[page]

    fun bottom(page: Int): Double = tops[page + 1]

    fun height(page: Int): Double = tops[page + 1] - tops[page]

    /** The page whose span from its top to its bottom holds [y]: the first page above the strip, the last below it. */
    fun pageAt(y: Double): Int = lastTopBefore(y, inclusive = true)

    /** The pages that show between [from] and [to]: the one at [from] and every later one whose top is above [to]. */
    fun pagesIn(from: Double, to: Double): IntRange {
        val first = pageAt(from)
        return first..maxOf(first, lastTopBefore(to, inclusive = false))
    }

    fun offsetOf(position: StripPosition): Double = top(position.page) + position.fraction * height(position.page)

    fun positionAt(y: Double): StripPosition {
        val page = pageAt(y)
        return StripPosition(page, ((y - top(page)) / height(page)).coerceIn(0.0, 1.0).toFloat()).named(pageCount)
    }

    /** The last page whose top is above [y], or at it when [inclusive]; the first page when none is. */
    private fun lastTopBefore(y: Double, inclusive: Boolean): Int {
        var low = 0
        var high = pageCount - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            val before = if (inclusive) tops[middle] <= y else tops[middle] < y
            if (before) low = middle else high = middle - 1
        }
        return low
    }

    override fun equals(other: Any?): Boolean = other is Strip && tops.contentEquals(other.tops)

    override fun hashCode(): Int = tops.contentHashCode()

    companion object {
        fun of(shapes: List<PageShape>): Strip {
            require(shapes.isNotEmpty()) { "a strip needs at least one page" }
            val known = shapes.mapNotNull { it.aspect }
            val unknown = if (known.isEmpty()) UNKNOWN_PAGE_ASPECT else known.average()
            val tops = DoubleArray(shapes.size + 1)
            shapes.forEachIndexed { i, shape -> tops[i + 1] = tops[i] + (shape.aspect ?: unknown) }
            return Strip(tops)
        }

        private val PageShape.aspect: Double?
            get() = (this as? PageShape.Known)?.takeIf { it.width > 0 && it.height > 0 }?.let { it.height.toDouble() / it.width }
    }
}

/** A point of a [Strip]: [fraction] of the way down [page], from 0 at its top to 1 at its bottom. */
data class StripPosition(val page: Int, val fraction: Float) {
    init {
        require(page >= 0) { "page $page" }
        require(fraction in 0f..1f) { "fraction $fraction is outside 0..1" }
    }

    /**
     * This point named by the page it is in, on a strip of [pageCount] pages: the bottom of a page is the top of the
     * next one, but at the end of the strip. A fraction that rounds to 1 names the bottom too.
     */
    fun named(pageCount: Int): StripPosition = if (fraction == 1f && page < pageCount - 1) StripPosition(page + 1, 0f) else this
}

/** The screen over a strip, in pixels, with the strip [zoom] times the width of the screen. */
data class StripScreen(val width: Float, val height: Float, val zoom: Float = 1f) {
    /** Screen pixels per page width. */
    val scale: Double get() = width.toDouble() * zoom
}

/** How the top of the screen moves on its own: a step between two places, or a glide after a fling. */
sealed interface ScrollMotion {
    /** The top of the screen at [nowMs], in pixels of the strip at the scale the motion started at. */
    fun topAt(nowMs: Long): Double

    fun isDoneAt(nowMs: Long): Boolean

    /** The same motion [by] pixels further down the strip. */
    fun shifted(by: Double): ScrollMotion

    /** A step from [from] to [to] at [STOP_SPEED_DP_PER_MS]. */
    data class Move(val from: Double, val to: Double, val motion: Motion) : ScrollMotion {
        override fun topAt(nowMs: Long): Double = from + (to - from) * motion.valueAt(nowMs)

        override fun isDoneAt(nowMs: Long): Boolean = motion.isDoneAt(nowMs)

        override fun shifted(by: Double): Move = copy(from = from + by, to = to + by)
    }

    /** A glide from [from] at [velocity] pixels per millisecond, slowing by [GLIDE_FRICTION_PER_S] for [durationMs]. */
    data class Glide(val from: Double, val velocity: Double, val startMs: Long, val durationMs: Long) : ScrollMotion {
        override fun topAt(nowMs: Long): Double {
            val t = (nowMs - startMs).coerceIn(0L, durationMs).toDouble()
            return from + velocity / FRICTION_PER_MS * (1.0 - exp(-FRICTION_PER_MS * t))
        }

        override fun isDoneAt(nowMs: Long): Boolean = nowMs - startMs >= durationMs

        override fun shifted(by: Double): Glide = copy(from = from + by)
    }
}

private const val FRICTION_PER_MS = GLIDE_FRICTION_PER_S / 1000.0

/**
 * Where the screen is over a [Strip] (docs/spec.md sections 2.5 and 2.6). [anchor] is the point of the strip at the
 * top of the screen. It holds a page and a fraction of it, so learning the shape of a page above leaves the screen
 * where it was, and a screen of another width shows the same place. [x] is the screen's left edge in pixels from the
 * strip's left edge, which matters only while a zoom makes the strip wider than the screen. Every position here is
 * clamped so the screen stays on the strip, and a strip shorter than the screen sits in its middle.
 */
data class StripScroll(val anchor: StripPosition = StripPosition(0, 0f), val x: Float = 0f, val motion: ScrollMotion? = null) {
    /** The top of the screen in pixels from the top of the strip, scaled to [screen]. */
    fun top(strip: Strip, screen: StripScreen): Double = clampedTop(strip.offsetOf(anchor) * screen.scale, strip, screen)

    /** The left of the screen in pixels from the left of the strip, scaled to [screen]. */
    fun left(screen: StripScreen): Float = axis(x.toDouble(), screen.scale, screen.width.toDouble()).toFloat()

    /** The pages on screen, top to bottom. */
    fun visible(strip: Strip, screen: StripScreen): IntRange {
        val top = top(strip, screen)
        return strip.pagesIn(top / screen.scale, (top + screen.height) / screen.scale)
    }

    /** Whether the bottom of the strip is on screen, so nothing is left to scroll toward the end. */
    fun endInView(strip: Strip, screen: StripScreen): Boolean =
        top(strip, screen) + screen.height >= strip.length * screen.scale - SAME_PX

    /** The page the reader is on: the one at the top of the screen, or the last page once the end is in view. */
    fun page(strip: Strip, screen: StripScreen): Int = if (endInView(strip, screen)) strip.pageCount - 1 else anchor.page

    /** A finger moved the strip by [dx] and [dy] pixels. */
    fun panned(dx: Float, dy: Float, strip: Strip, screen: StripScreen): StripScroll =
        placed(top(strip, screen) - dy, strip, screen).copy(x = axis(left(screen).toDouble() - dx, screen.scale, screen.width.toDouble()).toFloat())

    /**
     * A finger let go while moving at [fingerVelocity] pixels per second, positive downward. The strip keeps moving
     * the way the finger went, slower and slower, when it went faster than [GLIDE_MIN_DP_PER_S].
     */
    fun flung(fingerVelocity: Float, nowMs: Long, pxPerDp: Float, strip: Strip, screen: StripScreen): StripScroll {
        val dpPerS = (-fingerVelocity / pxPerDp).coerceIn(-GLIDE_MAX_DP_PER_S, GLIDE_MAX_DP_PER_S)
        if (abs(dpPerS) < GLIDE_MIN_DP_PER_S) return copy(motion = null)
        val durationMs = (ln(abs(dpPerS) / GLIDE_STOP_DP_PER_S) / FRICTION_PER_MS).roundToLong()
        return copy(motion = ScrollMotion.Glide(top(strip, screen), dpPerS * pxPerDp / 1000.0, nowMs, durationMs))
    }

    /**
     * One step toward [turn]: [SCROLL_STEP_SHARE] of the screen at [STOP_SPEED_DP_PER_MS], or less at an end of the
     * strip. `null` when the screen is at that end already.
     */
    fun stepped(turn: Turn, nowMs: Long, pxPerDp: Float, strip: Strip, screen: StripScreen): StripScroll? {
        val from = top(strip, screen)
        val step = screen.height * SCROLL_STEP_SHARE * if (turn == Turn.Forward) 1.0 else -1.0
        val to = clampedTop(from + step, strip, screen)
        if (abs(to - from) < SAME_PX) return null
        val durationMs = ceil(abs(to - from) / pxPerDp / STOP_SPEED_DP_PER_MS).roundToLong()
        return copy(motion = ScrollMotion.Move(from, to, Motion(nowMs, durationMs)))
    }

    /** The screen at [nowMs] of its motion. A glide ends at an end of the strip. */
    fun ticked(nowMs: Long, strip: Strip, screen: StripScreen): StripScroll {
        val motion = motion ?: return this
        val wanted = motion.topAt(nowMs)
        val top = clampedTop(wanted, strip, screen)
        val done = motion.isDoneAt(nowMs) || motion is ScrollMotion.Glide && abs(top - wanted) >= SAME_PX
        return placed(top, strip, screen).copy(motion = if (done) null else motion)
    }

    /** The screen as the next input finds it: a step at its end at once, a glide caught where it is. */
    fun settled(strip: Strip, screen: StripScreen): StripScroll = when (val motion = motion) {
        null -> this
        is ScrollMotion.Move -> placed(motion.to, strip, screen).copy(motion = null)
        is ScrollMotion.Glide -> copy(motion = null)
    }

    /**
     * The screen over [after], the strip once the shape of a page is known, from over [before]. The anchor stays, and
     * a running motion moves as far as the anchor did, so it goes on from what the screen shows.
     */
    fun relaid(before: Strip, after: Strip, screen: StripScreen): StripScroll {
        val motion = motion ?: return this
        return copy(motion = motion.shifted((after.offsetOf(anchor) - before.offsetOf(anchor)) * screen.scale))
    }

    /** The zoom changed from [before] to [after] about [focus], in screen pixels, which stays over the same point. */
    fun zoomed(before: StripScreen, after: StripScreen, focus: Point, strip: Strip): StripScroll {
        val ratio = after.scale / before.scale
        val top = (top(strip, before) + focus.y) * ratio - focus.y
        val left = (left(before) + focus.x) * ratio - focus.x
        return placed(top, strip, after).copy(x = axis(left, after.scale, after.width.toDouble()).toFloat(), motion = null)
    }

    /** The top of the screen at [top], clamped, with [x] as it is. */
    private fun placed(top: Double, strip: Strip, screen: StripScreen): StripScroll {
        val clamped = clampedTop(top, strip, screen)
        return copy(anchor = strip.positionAt(clamped / screen.scale))
    }

    companion object {
        /** The screen with the top of [page] at its top, as a jump to a page and opening the book show it. */
        fun at(page: Int, fraction: Float = 0f): StripScroll = StripScroll(StripPosition(page, fraction))

        private fun clampedTop(top: Double, strip: Strip, screen: StripScreen): Double =
            axis(top, strip.length * screen.scale, screen.height.toDouble())

        private fun axis(at: Double, content: Double, screen: Double): Double =
            if (content <= screen) (content - screen) / 2.0 else at.coerceIn(0.0, content - screen)
    }
}
