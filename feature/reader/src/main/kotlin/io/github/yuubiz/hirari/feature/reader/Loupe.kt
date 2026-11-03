package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.LoupeFrame
import io.github.yuubiz.hirari.core.model.LoupeZoom
import io.github.yuubiz.hirari.core.model.Motion
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Size
import kotlin.math.abs

/** How long the loupe takes to move to another quarter (docs/spec.md section 4.8, ルーペ). */
const val LOUPE_MOVE_MS = 150L

/** How far from the lines between the quarters a finger goes before the loupe may move again, in dp. */
const val LOUPE_SETTLE_DP = 24f

/** One of the four quarters the screen's middle lines make. */
enum class Quadrant(val right: Boolean, val bottom: Boolean) {
    TopLeft(false, false),
    TopRight(true, false),
    BottomLeft(false, true),
    BottomRight(true, true),
    ;

    /** The quarter across the middle, which is the farthest from any point in this one. */
    val opposite: Quadrant get() = entries.first { it.right != right && it.bottom != bottom }

    companion object {
        fun of(point: Point, screen: Size): Quadrant =
            entries.first { it.right == (point.x >= screen.width / 2f) && it.bottom == (point.y >= screen.height / 2f) }
    }
}

/** The loupe's width and height as a share of the screen's. */
val LoupeFrame.share: Float
    get() = when (this) {
        LoupeFrame.Small -> 7f / 16f
        LoupeFrame.Medium -> 8f / 16f
        LoupeFrame.Large -> 9f / 16f
    }

/**
 * How much larger the loupe shows the screen, where one screen pixel shows [displayScale] pixels of the page's own
 * image. The magnifications of the source image never go below the screen's own, so the loupe never shrinks.
 */
fun LoupeZoom.magnification(displayScale: Float): Float = when (this) {
    LoupeZoom.View150 -> 1.5f
    LoupeZoom.View200 -> 2f
    LoupeZoom.View250 -> 2.5f
    LoupeZoom.View300 -> 3f
    LoupeZoom.Source100 -> (1f / displayScale).coerceAtLeast(1f)
    LoupeZoom.Source200 -> (2f / displayScale).coerceAtLeast(1f)
    LoupeZoom.Source300 -> (3f / displayScale).coerceAtLeast(1f)
}

/** Whether [this] reads the page's own pixels rather than the prepared view. */
val LoupeZoom.readsSource: Boolean get() = this == LoupeZoom.Source100 || this == LoupeZoom.Source200 || this == LoupeZoom.Source300

/** A move of the loupe from [from] to the quarter the [Loupe] names. */
data class LoupeMove(val from: Quadrant, val motion: Motion)

/**
 * ルーペ (docs/spec.md section 4.8): the [finger] in screen pixels, and the [quadrant] the loupe sits in or moves to.
 * After a move it stays put until [settled], once the finger is [LOUPE_SETTLE_DP] away from the middle lines.
 */
data class Loupe(val finger: Point, val quadrant: Quadrant, val move: LoupeMove? = null, val settled: Boolean = true) {
    /** The finger at [point]. Entering the loupe's quarter sends it to the one across the middle. */
    fun moved(point: Point, screen: Size, density: Float, nowMs: Long): Loupe {
        val center = Point(screen.width / 2f, screen.height / 2f)
        val clear = minOf(abs(point.x - center.x), abs(point.y - center.y)) >= LOUPE_SETTLE_DP * density
        val under = Quadrant.of(point, screen)
        if ((settled || clear) && under == quadrant) {
            return Loupe(point, under.opposite, LoupeMove(quadrant, Motion(nowMs, LOUPE_MOVE_MS)), settled = false)
        }
        return copy(finger = point, settled = settled || clear)
    }

    /** The loupe after a frame at [nowMs]: a move that is over is gone. */
    fun at(nowMs: Long): Loupe = if (move?.motion?.isDoneAt(nowMs) == true) copy(move = null) else this

    /** Where the loupe is on a [screen] at [nowMs], moving or resting in the outer corner of its quarter. */
    fun rectAt(nowMs: Long, screen: Size, frame: LoupeFrame): Rect {
        val to = cornerRect(quadrant, screen, frame)
        val move = move ?: return to
        val from = cornerRect(move.from, screen, frame)
        val t = move.motion.valueAt(nowMs)
        fun mix(a: Float, b: Float) = a + (b - a) * t
        return Rect(mix(from.left, to.left), mix(from.top, to.top), mix(from.right, to.right), mix(from.bottom, to.bottom))
    }

    companion object {
        /** The loupe a hold at [finger] brings up, in the quarter across the middle from it. */
        fun at(finger: Point, screen: Size): Loupe = Loupe(finger, Quadrant.of(finger, screen).opposite)

        fun cornerRect(quadrant: Quadrant, screen: Size, frame: LoupeFrame): Rect {
            val width = screen.width * frame.share
            val height = screen.height * frame.share
            val left = if (quadrant.right) screen.width - width else 0f
            val top = if (quadrant.bottom) screen.height - height else 0f
            return Rect(left, top, left + width, top + height)
        }
    }
}

/**
 * What the loupe shows: the screen around [finger] scaled by [magnification] about the loupe's center. A point
 * [p] of the screen shows at `center + (p − finger) × magnification`.
 */
data class LoupeView(val rect: Rect, val finger: Point, val magnification: Float) {
    val center: Point get() = Point((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)

    fun shown(p: Point): Point = Point(center.x + (p.x - finger.x) * magnification, center.y + (p.y - finger.y) * magnification)

    /** The part of the screen the loupe shows. */
    val source: Rect
        get() {
            val halfWidth = rect.width / 2f / magnification
            val halfHeight = rect.height / 2f / magnification
            return Rect(finger.x - halfWidth, finger.y - halfHeight, finger.x + halfWidth, finger.y + halfHeight)
        }
}
