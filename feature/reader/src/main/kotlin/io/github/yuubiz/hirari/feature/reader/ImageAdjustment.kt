package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.ImageAdjust
import kotlin.math.abs
import kotlin.math.roundToInt

/** The gain one step of `adjustLevel` adds (docs/spec.md section 4.10). */
const val LEVEL_STEP = 0.1f

/** The weights of BT.709 that turn sRGB into its brightness, for グレースケール. */
private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

/**
 * The colour steps of 画像補正 as one 4×5 colour matrix in the row order of `android.graphics.ColorMatrix`, with
 * the offsets in 0 to 255: グレースケール, then 白黒反転, then 明るさ (docs/spec.md section 4.8). `null` when
 * nothing changes the colours, so the draw needs no filter. シャープ is not a colour step; it happens when the
 * page is prepared.
 */
fun ImageAdjust.colorMatrix(): FloatArray? {
    var matrix = IDENTITY
    if (mono) matrix = colours { _, column -> LUMA[column] } * matrix
    if (negative) matrix = colours(offset = 1f) { row, column -> if (row == column) -1f else 0f } * matrix
    if (level != 0) matrix = level(level) * matrix
    if (matrix == IDENTITY) return null
    return FloatArray(20) { i ->
        val row = i / 5
        val column = i % 5
        if (column == 4) matrix.offsets[row] * 255f else matrix.gains[row * 4 + column]
    }
}

/**
 * 明るさ: a gain of 1 + [LEVEL_STEP] × |level|. A brighter level keeps black and lifts the rest, so the lightest
 * greys reach white. A darker level keeps white and lowers the rest, so the darkest greys reach black.
 */
private fun level(level: Int): Affine {
    val gain = 1f + LEVEL_STEP * abs(level)
    return colours(offset = if (level < 0) 1f - gain else 0f) { row, column -> if (row == column) gain else 0f }
}

/** `out = gains × in + offsets` on colours in 0 to 1, rows R, G, B, A, kept off the platform so it tests on the JVM. */
private class Affine(val gains: FloatArray, val offsets: FloatArray) {
    /** This step after [first]. */
    operator fun times(first: Affine): Affine = Affine(
        FloatArray(16) { i -> (0 until 4).fold(0f) { sum, k -> sum + gains[i / 4 * 4 + k] * first.gains[k * 4 + i % 4] } },
        FloatArray(4) { row -> (0 until 4).fold(offsets[row]) { sum, k -> sum + gains[row * 4 + k] * first.offsets[k] } },
    )

    override fun equals(other: Any?): Boolean = other is Affine && gains.contentEquals(other.gains) && offsets.contentEquals(other.offsets)

    override fun hashCode(): Int = 31 * gains.contentHashCode() + offsets.contentHashCode()
}

private val IDENTITY = colours { row, column -> if (row == column) 1f else 0f }

/** A step whose colour rows come from [gain] and add [offset], and which passes alpha through. */
private fun colours(offset: Float = 0f, gain: (row: Int, column: Int) -> Float): Affine = Affine(
    FloatArray(16) { i ->
        val row = i / 4
        val column = i % 4
        when {
            row == 3 -> if (column == 3) 1f else 0f
            column == 3 -> 0f
            else -> gain(row, column)
        }
    },
    floatArrayOf(offset, offset, offset, 0f),
)

/** The strength of シャープ: each pixel moves this much further from each of its four neighbours. */
const val SHARPEN_AMOUNT = 0.2f

/**
 * シャープ on an ARGB image of [width] × [height], one row at a time: a 3×3 Laplacian of [SHARPEN_AMOUNT], reading
 * past an edge as the edge pixel. Alpha is kept. [read] fills a row of the source and [write] takes a finished one.
 * Only four rows are held, and row y is written after row y + 1 is read, so [write] may replace the source in place.
 */
fun sharpenRows(width: Int, height: Int, read: (y: Int, into: IntArray) -> Unit, write: (y: Int, row: IntArray) -> Unit) {
    var up = IntArray(width)
    var middle = IntArray(width)
    var down = IntArray(width)
    val out = IntArray(width)
    read(0, middle)
    middle.copyInto(up)
    for (y in 0 until height) {
        if (y + 1 < height) read(y + 1, down) else middle.copyInto(down)
        sharpenRow(up, middle, down, out)
        write(y, out)
        val spare = up
        up = middle
        middle = down
        down = spare
    }
}

private fun sharpenRow(up: IntArray, middle: IntArray, down: IntArray, out: IntArray) {
    val center = 1f + 4f * SHARPEN_AMOUNT
    val last = middle.size - 1
    for (x in middle.indices) {
        val p = middle[x]
        val a = up[x]
        val b = down[x]
        val c = middle[(x - 1).coerceAtLeast(0)]
        val d = middle[(x + 1).coerceAtMost(last)]
        var argb = p and ALPHA
        var shift = 16
        while (shift >= 0) {
            val around = (a shr shift and 0xFF) + (b shr shift and 0xFF) + (c shr shift and 0xFF) + (d shr shift and 0xFF)
            val value = (center * (p shr shift and 0xFF) - SHARPEN_AMOUNT * around).roundToInt().coerceIn(0, 255)
            argb = argb or (value shl shift)
            shift -= 8
        }
        out[x] = argb
    }
}

private const val ALPHA = 0xFF000000.toInt()
