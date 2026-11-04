package io.github.yuubiz.hirari.feature.reader

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.Choreographer
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.PreparedImage
import io.github.yuubiz.hirari.core.image.RawPage
import io.github.yuubiz.hirari.core.image.RawResult
import io.github.yuubiz.hirari.core.image.scanMargins
import io.github.yuubiz.hirari.core.image.sharpened
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.ui.TraceSection
import io.github.yuubiz.hirari.feature.reader.prefetch.MadePage
import io.github.yuubiz.hirari.feature.reader.prefetch.PageLoader
import io.github.yuubiz.hirari.feature.reader.prefetch.PageSteps
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.math.roundToInt

/**
 * Pages of [book] as HARDWARE bitmaps (Q2 in docs/spec.md section 10). The steps after scaling and the upload to
 * the GPU happen here, on [dispatcher], so the first frame that draws a view does not wait for them.
 */
class BookPageLoader(private val book: OpenBook, private val dispatcher: CoroutineDispatcher) : PageLoader<RawPage, Bitmap> {
    override fun prioritize(pages: List<Int>) = book.prioritize(pages)

    override suspend fun invalidate(page: Int) = book.invalidate(page)
    override fun fromStash(raw: RawPage): Boolean = book.fromStash(raw)

    override suspend fun raw(page: Int): RawResult = book.raw(page)

    override fun bytesOf(raw: RawPage): Long = raw.byteCount

    /**
     * The size the book declares for the entry, or [UNDECLARED_RAW_BYTES] when it declares none. A PDF page is
     * drawn from the open document and holds nothing.
     */
    override fun rawEstimate(page: Int): Long =
        if (book.container == Container.Pdf) 0L else book.pages[page].declaredSize ?: UNDECLARED_RAW_BYTES

    override suspend fun shape(page: Int, raw: RawPage?): PageResult<PageShape.Known> = book.shape(page, raw)

    override suspend fun prepare(page: Int, size: PixelSize, steps: PageSteps, raw: RawPage?, readMargins: Boolean): PageResult<MadePage<Bitmap>> {
        val trace = TraceSection.begin(TRACE_PREPARE)
        return try {
            smallerOnOutOfMemory(size) { prepareAt(page, it, steps, raw, readMargins) }
        } finally {
            trace.end()
        }
    }

    /**
     * The whole page is decoded at the scale where its part matches [size], so a trimmed page keeps the detail of
     * the screen. Throws [OutOfMemoryError] when the steps after scaling run out of memory, with every bitmap
     * released.
     */
    private suspend fun prepareAt(page: Int, size: PixelSize, steps: PageSteps, raw: RawPage?, readMargins: Boolean): PageResult<MadePage<Bitmap>> {
        val part = steps.part
        val shown = PixelSize((size.width / part.width).roundToInt().coerceAtLeast(1), (size.height / part.height).roundToInt().coerceAtLeast(1))
        val decoded = if (steps.rotation.swapsSides) PixelSize(shown.height, shown.width) else shown
        return when (val result = book.page(page, decoded, raw)) {
            is PageResult.Ready -> handOff(
                dispatcher,
                result.image,
                releaseInput = { (it as PreparedImage.Whole).bitmap.recycle() },
                releaseOutput = { (it as? PageResult.Ready)?.image?.image?.recycle() },
            ) { finish(it, steps, readMargins) }
            is PageResult.Failed -> result
            is PageResult.Unsupported -> result
        }
    }

    /** The reader asks for whole pages only, never tiles, so every page it shows can be trimmed. */
    private fun finish(image: PreparedImage, steps: PageSteps, readMargins: Boolean): PageResult<MadePage<Bitmap>> {
        var bitmap = (image as PreparedImage.Whole).bitmap
        return try {
            bitmap = bitmap.replacedBy { rotated(it, steps.rotation) }
            val margins = if (readMargins) scanMargins(bitmap) else null
            bitmap = bitmap.replacedBy { cropped(it, steps.part) }
            if (steps.sharpen) bitmap = bitmap.replacedBy(::sharpened)
            val hardware = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (hardware == null) {
                PageResult.Ready(MadePage(bitmap, margins))
            } else {
                bitmap.recycle()
                PageResult.Ready(MadePage(hardware, margins))
            }
        } catch (e: OutOfMemoryError) {
            bitmap.recycle()
            throw e
        }
    }
}

/** The trace section of docs/spec.md section 5.2 for the preparation of one page: decode, the steps after it, and the upload. */
private const val TRACE_PREPARE = "hirari.prepare"

/** What the raw data of an entry with no declared size is taken to hold: a page of the standard corpus (section 5.3). */
private const val UNDECLARED_RAW_BYTES = 1_500_000L

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

private fun cropped(bitmap: Bitmap, part: PagePart): Bitmap {
    if (part == PagePart.Whole) return bitmap
    val rect = part.pixelsOf(PixelSize(bitmap.width, bitmap.height))
    return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
}

/**
 * Recycles [bitmap] two frames from now, on the main thread. The frame being recorded may still draw it, so it
 * stays valid until a frame without it has been drawn.
 */
fun recycleAfterFrames(bitmap: Bitmap) {
    val choreographer = Choreographer.getInstance()
    choreographer.postFrameCallback { choreographer.postFrameCallback { bitmap.recycle() } }
}
