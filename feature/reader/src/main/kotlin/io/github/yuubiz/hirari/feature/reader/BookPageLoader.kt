package io.github.yuubiz.hirari.feature.reader

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.Choreographer
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.PreparedImage
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.feature.reader.prefetch.PageLoader
import io.github.yuubiz.hirari.feature.reader.prefetch.PageSteps
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Pages of [book] as HARDWARE bitmaps (Q2 in docs/spec.md section 10). The steps after scaling and the upload to
 * the GPU happen here, on [dispatcher], so the first frame that draws a view does not wait for them.
 */
class BookPageLoader(private val book: OpenBook, private val dispatcher: CoroutineDispatcher) : PageLoader<Bitmap> {
    override suspend fun shape(page: Int): PageResult<PageShape.Known> = book.shape(page)

    override suspend fun prepare(page: Int, size: PixelSize, steps: PageSteps): PageResult<Bitmap> =
        smallerOnOutOfMemory(size) { prepareAt(page, it, steps) }

    /** Throws [OutOfMemoryError] when the steps after scaling run out of memory, with every bitmap released. */
    private suspend fun prepareAt(page: Int, size: PixelSize, steps: PageSteps): PageResult<Bitmap> {
        val shown = if (steps.crop == null) size else PixelSize(size.width * 2, size.height)
        val decoded = if (steps.rotation.swapsSides) PixelSize(shown.height, shown.width) else shown
        return when (val result = book.page(page, decoded)) {
            is PageResult.Ready -> handOff(
                dispatcher,
                result.image,
                releaseInput = { (it as PreparedImage.Whole).bitmap.recycle() },
                releaseOutput = { (it as? PageResult.Ready)?.image?.recycle() },
            ) { finish(it, steps) }
            is PageResult.Failed -> result
            is PageResult.Unsupported -> result
        }
    }

    private fun finish(image: PreparedImage, steps: PageSteps): PageResult<Bitmap> {
        var bitmap = (image as PreparedImage.Whole).bitmap
        return try {
            bitmap = bitmap.replacedBy { rotated(it, steps.rotation) }
            bitmap = bitmap.replacedBy { cropped(it, steps.crop) }
            if (steps.sharpen) bitmap = bitmap.replacedBy(::sharpenedBitmap)
            val hardware = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (hardware == null) {
                PageResult.Ready(bitmap)
            } else {
                bitmap.recycle()
                PageResult.Ready(hardware)
            }
        } catch (e: OutOfMemoryError) {
            bitmap.recycle()
            throw e
        }
    }
}

/**
 * [attempt] at [size], and once more at half of each side when it runs out of memory, as the decoder does for
 * its own step (docs/spec.md section 6.1).
 */
internal suspend fun <T> smallerOnOutOfMemory(size: PixelSize, attempt: suspend (PixelSize) -> PageResult<T>): PageResult<T> = try {
    attempt(size)
} catch (_: OutOfMemoryError) {
    try {
        attempt(PixelSize((size.width / 2).coerceAtLeast(1), (size.height / 2).coerceAtLeast(1)))
    } catch (_: OutOfMemoryError) {
        PageResult.Failed(FailureReason.ReadError, retryable = true)
    }
}

/** The result of [step], recycling this bitmap when the step made another. */
private inline fun Bitmap.replacedBy(step: (Bitmap) -> Bitmap): Bitmap {
    val next = step(this)
    if (next !== this) recycle()
    return next
}

private fun rotated(bitmap: Bitmap, rotation: Rotation): Bitmap {
    if (rotation == Rotation.None) return bitmap
    val matrix = Matrix().apply { postRotate(rotation.degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun cropped(bitmap: Bitmap, crop: Side?): Bitmap {
    if (crop == null) return bitmap
    val width = (bitmap.width / 2).coerceAtLeast(1)
    return Bitmap.createBitmap(bitmap, if (crop == Side.Left) 0 else bitmap.width - width, 0, width, bitmap.height)
}

/**
 * シャープ a row at a time, into this bitmap when it is mutable, so the work beside the bitmaps is four rows. A
 * bitmap already on the GPU has no pixels to read here, so it stays as it is.
 */
private fun sharpenedBitmap(bitmap: Bitmap): Bitmap {
    if (bitmap.config == Bitmap.Config.HARDWARE) return bitmap
    val width = bitmap.width
    val out = if (bitmap.isMutable) bitmap else Bitmap.createBitmap(width, bitmap.height, Bitmap.Config.ARGB_8888)
    sharpenRows(
        width,
        bitmap.height,
        read = { y, row -> bitmap.getPixels(row, 0, width, 0, y, width, 1) },
        write = { y, row -> out.setPixels(row, 0, width, 0, y, width, 1) },
    )
    return out
}

/**
 * Recycles [bitmap] two frames from now, on the main thread. The frame being recorded may still draw it, so it
 * stays valid until a frame without it has been drawn.
 */
fun recycleAfterFrames(bitmap: Bitmap) {
    val choreographer = Choreographer.getInstance()
    choreographer.postFrameCallback { choreographer.postFrameCallback { bitmap.recycle() } }
}
