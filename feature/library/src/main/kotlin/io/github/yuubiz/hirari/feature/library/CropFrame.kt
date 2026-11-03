package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReadingDirection
import kotlin.math.max
import kotlin.math.min

/** The part of the grabbed frame that a drag moves (docs/spec.md section 4.4, 範囲を選んで表紙にする). */
enum class Grip(val left: Boolean = false, val right: Boolean = false, val top: Boolean = false, val bottom: Boolean = false) {
    Inside,
    Left(left = true),
    Right(right = true),
    Top(top = true),
    Bottom(bottom = true),
    TopLeft(left = true, top = true),
    TopRight(right = true, top = true),
    BottomLeft(left = true, bottom = true),
    BottomRight(right = true, bottom = true),
}

/** What a screen reader's action does to the frame in place of a drag. */
enum class FrameStep { Left, Right, Up, Down, Larger, Smaller }

/**
 * The frame on the page while a range is chosen, as fractions of the page's width and height. A drag works on the
 * frame the finger took hold of and the whole distance since, so the frame follows the finger however it is
 * clamped on the way. It is always inside the page and at least [MIN] long on each side.
 */
data class CropFrame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** The same frame moved by ([dx], [dy]), stopping at the edges of the page. */
    fun moved(dx: Float, dy: Float): CropFrame {
        val x = dx.coerceIn(-left, 1f - right)
        val y = dy.coerceIn(-top, 1f - bottom)
        return CropFrame(left + x, top + y, right + x, bottom + y)
    }

    /** The frame with the edges of [grip] moved by ([dx], [dy]), each stopping at the page or at [MIN] from the opposite edge. */
    fun resized(grip: Grip, dx: Float, dy: Float): CropFrame {
        if (grip == Grip.Inside) return moved(dx, dy)
        return CropFrame(
            left = if (grip.left) (left + dx).coerceIn(0f, right - MIN) else left,
            top = if (grip.top) (top + dy).coerceIn(0f, bottom - MIN) else top,
            right = if (grip.right) (right + dx).coerceIn(left + MIN, 1f) else right,
            bottom = if (grip.bottom) (bottom + dy).coerceIn(top + MIN, 1f) else bottom,
        )
    }

    /**
     * What a finger at ([x], [y]) takes hold of, or `null` for a place off the frame. [slopX] and [slopY] are how far
     * from an edge a finger still takes it, in fractions of the page; they are cut to a third of the frame so its
     * inside can always be taken. A corner wins over an edge, and an edge over the inside.
     */
    fun gripAt(x: Float, y: Float, slopX: Float, slopY: Float): Grip? {
        val sx = min(slopX, width / 3)
        val sy = min(slopY, height / 3)
        if (x < left - sx || x > right + sx || y < top - sy || y > bottom + sy) return null
        val nearLeft = x <= left + sx
        val nearRight = x >= right - sx
        val nearTop = y <= top + sy
        val nearBottom = y >= bottom - sy
        return when {
            nearLeft && nearTop -> Grip.TopLeft
            nearRight && nearTop -> Grip.TopRight
            nearLeft && nearBottom -> Grip.BottomLeft
            nearRight && nearBottom -> Grip.BottomRight
            nearLeft -> Grip.Left
            nearRight -> Grip.Right
            nearTop -> Grip.Top
            nearBottom -> Grip.Bottom
            else -> Grip.Inside
        }
    }

    /** The frame after [step], for a user who cannot drag it (docs/spec.md section 6.7). A step stops at the page and at [MIN]. */
    fun stepped(step: FrameStep): CropFrame = when (step) {
        FrameStep.Left -> moved(-STEP, 0f)
        FrameStep.Right -> moved(STEP, 0f)
        FrameStep.Up -> moved(0f, -STEP)
        FrameStep.Down -> moved(0f, STEP)
        FrameStep.Larger -> resized(Grip.TopLeft, -STEP, -STEP).resized(Grip.BottomRight, STEP, STEP)
        FrameStep.Smaller -> resized(Grip.TopLeft, STEP, STEP).resized(Grip.BottomRight, -STEP, -STEP)
    }

    fun toCrop(): CoverCrop = CoverCrop.ofFractions(left, top, right, bottom)

    companion object {
        const val MIN = CoverCrop.MIN_SIDE.toFloat() / CoverCrop.SCALE

        /** How far a [FrameStep] moves an edge, as a fraction of the page. */
        const val STEP = 0.05f

        val Whole = CropFrame(0f, 0f, 1f, 1f)

        fun of(crop: CoverCrop) = CropFrame(
            crop.left.toFloat() / CoverCrop.SCALE,
            crop.top.toFloat() / CoverCrop.SCALE,
            crop.right.toFloat() / CoverCrop.SCALE,
            crop.bottom.toFloat() / CoverCrop.SCALE,
        )

        /**
         * The frame a page opens with when it has no range (docs/spec.md section 4.4): the largest frame of the
         * proportion [ratio] (width over height) that fits the part of the page the automatic cover shows, in the
         * middle of it. That part is the page, or for a page wider than high the half on the side of [direction].
         */
        fun initial(shape: PageShape.Known, direction: ReadingDirection, ratio: Float = COVER_RATIO): CropFrame {
            val wide = shape.isWide
            val from = if (wide && direction == ReadingDirection.RightToLeft) 0.5f else 0f
            val to = if (wide && direction == ReadingDirection.LeftToRight) 0.5f else 1f
            val partWidth = (to - from) * shape.width
            val partHeight = shape.height.toFloat()
            // Pixels of the frame: the whole part when it is already thinner than the ratio, else as wide as the ratio allows.
            val frameWidth = min(partWidth, ratio * partHeight)
            val frameHeight = min(partHeight, partWidth / ratio)
            val fractionWidth = frameWidth / shape.width
            val fractionHeight = frameHeight / shape.height
            val left = (from + to) / 2 - fractionWidth / 2
            val top = (1f - fractionHeight) / 2
            return CropFrame(
                max(left, from).coerceIn(0f, 1f),
                top.coerceIn(0f, 1f),
                min(left + fractionWidth, to).coerceIn(0f, 1f),
                (top + fractionHeight).coerceIn(0f, 1f),
            ).normalized()
        }
    }
}

/** Where the frame starts for the page [entry], and whether this page is the book's cover with a range already. */
internal data class OpeningFrame(val frame: CropFrame, val ranged: Boolean)

/**
 * The range stored for the book belongs to the page it was chosen on ([storedEntry]); only that page opens with it.
 * Any other page opens with the frame of [CropFrame.initial].
 */
internal fun openingFrame(storedEntry: Int?, storedCrop: CoverCrop?, entry: Int, shape: PageShape.Known, direction: ReadingDirection): OpeningFrame {
    val here = storedCrop?.takeIf { storedEntry == entry }
    return OpeningFrame(here?.let(CropFrame::of) ?: CropFrame.initial(shape, direction), ranged = here != null)
}

private fun CropFrame.normalized(): CropFrame {
    val crop = toCrop()
    return CropFrame.of(crop)
}
