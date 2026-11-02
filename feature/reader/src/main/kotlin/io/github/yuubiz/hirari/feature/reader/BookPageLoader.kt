package io.github.yuubiz.hirari.feature.reader

import android.graphics.Bitmap
import android.view.Choreographer
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.PreparedImage
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.feature.reader.prefetch.PageLoader
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Pages of [book] as HARDWARE bitmaps (Q2 in docs/spec.md section 10). The upload to the GPU happens here, on
 * [dispatcher], so the first frame that draws a view does not wait for it.
 */
class BookPageLoader(private val book: OpenBook, private val dispatcher: CoroutineDispatcher) : PageLoader<Bitmap> {
    override suspend fun shape(page: Int): PageResult<PageShape.Known> = book.shape(page)

    override suspend fun prepare(page: Int, size: PixelSize, crop: Side?): PageResult<Bitmap> {
        val whole = if (crop == null) size else PixelSize(size.width * 2, size.height)
        return when (val result = book.page(page, whole)) {
            is PageResult.Ready -> handOff(
                dispatcher,
                result.image,
                releaseInput = { (it as PreparedImage.Whole).bitmap.recycle() },
                releaseOutput = { (it as? PageResult.Ready)?.image?.recycle() },
            ) { toHardware(it, crop) }
            is PageResult.Failed -> result
            is PageResult.Unsupported -> result
        }
    }

    private fun toHardware(image: PreparedImage, crop: Side?): PageResult<Bitmap> {
        val decoded = (image as PreparedImage.Whole).bitmap
        return try {
            val half = if (crop == null) {
                decoded
            } else {
                val width = (decoded.width / 2).coerceAtLeast(1)
                Bitmap.createBitmap(decoded, if (crop == Side.Left) 0 else decoded.width - width, 0, width, decoded.height)
            }
            if (half !== decoded) decoded.recycle()
            val hardware = half.copy(Bitmap.Config.HARDWARE, false)
            if (hardware == null) {
                PageResult.Ready(half)
            } else {
                half.recycle()
                PageResult.Ready(hardware)
            }
        } catch (_: OutOfMemoryError) {
            decoded.recycle()
            PageResult.Failed(FailureReason.ReadError, retryable = true)
        }
    }
}

/**
 * Recycles [bitmap] two frames from now, on the main thread. The frame being recorded may still draw it, so it
 * stays valid until a frame without it has been drawn.
 */
fun recycleAfterFrames(bitmap: Bitmap) {
    val choreographer = Choreographer.getInstance()
    choreographer.postFrameCallback { choreographer.postFrameCallback { bitmap.recycle() } }
}
