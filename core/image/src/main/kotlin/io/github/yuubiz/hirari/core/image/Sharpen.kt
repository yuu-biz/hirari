package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * シャープ a row at a time, into this bitmap when it is mutable, so the work beside the bitmaps is four rows. A
 * bitmap already on the GPU has no pixels to read here, so it stays as it is.
 */
fun sharpened(bitmap: Bitmap): Bitmap {
    if (bitmap.config == Bitmap.Config.HARDWARE) return bitmap
    val width = bitmap.width
    val out = if (bitmap.isMutable) bitmap else Bitmap.createBitmap(width, bitmap.height, Bitmap.Config.ARGB_8888)
    try {
        sharpenRows(
            width,
            bitmap.height,
            read = { y, row -> bitmap.getPixels(row, 0, width, 0, y, width, 1) },
            write = { y, row -> out.setPixels(row, 0, width, 0, y, width, 1) },
        )
    } catch (e: Throwable) {
        if (out !== bitmap) out.recycle()
        throw e
    }
    return out
}

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
