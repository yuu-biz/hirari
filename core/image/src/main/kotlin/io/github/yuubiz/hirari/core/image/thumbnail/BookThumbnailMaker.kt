package io.github.yuubiz.hirari.core.image.thumbnail

import android.graphics.Bitmap
import android.os.Build
import io.github.yuubiz.hirari.core.archive.ListingNeed
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.PreparedImage
import io.github.yuubiz.hirari.core.image.RawPage
import io.github.yuubiz.hirari.core.image.Tiling
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReadingDirection
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** Makes the WebP of one cover. The orchestrator in [Thumbnails] calls it with a lane's permit held. */
internal fun interface ThumbnailMaker {
    suspend fun make(book: ThumbSubject.Book, sizePx: Int, direction: ReadingDirection): Made
}

/** Shows one page whole, for choosing a part of it as the cover. Called with a lane's permit held, like [ThumbnailMaker]. */
internal fun interface PagePreviewer {
    suspend fun preview(book: ThumbSubject.Book, entryIndex: Int, maxSidePx: Int): PagePreview
}

/**
 * Opens the book, decodes the cover page at the size of the thumbnail through the plan of docs/spec.md section
 * 3.7, cuts the front half from a wide cover or the part the user chose, and encodes it. [decoding] is the
 * instance of the thumbnails, so its dispatcher is not the reader's.
 */
internal class BookThumbnailMaker(@Volatile var decoding: ImageDecoding) : ThumbnailMaker, PagePreviewer {
    override suspend fun make(book: ThumbSubject.Book, sizePx: Int, direction: ReadingDirection): Made {
        val decoding = decoding
        val open = when (val opened = openOrNull(book, decoding)) {
            is Opened.Book -> opened.book
            is Opened.Not -> return opened.made
        }
        return open.use { cover(it, book.coverEntry, book.firstEntry, book.coverCrop, sizePx, direction, decoding) }
    }

    override suspend fun preview(book: ThumbSubject.Book, entryIndex: Int, maxSidePx: Int): PagePreview {
        val decoding = decoding
        val open = when (val opened = openOrNull(book, decoding)) {
            is Opened.Book -> opened.book
            is Opened.Not -> return opened.made.asPreview()
        }
        return open.use { page(it, entryIndex, maxSidePx) }
    }

    private sealed interface Opened {
        class Book(val book: OpenBook) : Opened

        class Not(val made: Made) : Opened
    }

    private suspend fun openOrNull(book: ThumbSubject.Book, decoding: ImageDecoding): Opened {
        val opened = try {
            book.open(decoding)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Opened.Not(Made.Unavailable)
        }
        return when (opened) {
            is BookResult.Failed -> Opened.Not(opened.reason.made())
            is BookResult.Opened -> Opened.Book(opened.book)
        }
    }

    private suspend fun cover(open: OpenBook, coverEntry: Int?, firstEntry: Int?, crop: CoverCrop?, sizePx: Int, direction: ReadingDirection, decoding: ImageDecoding): Made {
        val listingFinal = open.indexError?.made() != Made.Unavailable
        val page = coverPage(open.pages, coverEntry ?: firstEntry, listingFinal, counted = open.need is ListingNeed.Images) ?: return open.indexError?.made() ?: Made.Unreadable
        val raw: RawPage = when (val read = open.raw(page.index)) {
            is PageResult.Ready -> read.image
            else -> return read.made()
        }
        val shape = when (val read = open.shape(page.index, raw)) {
            is PageResult.Ready -> read.image
            else -> return read.made()
        }
        val range = rangeOf(page, coverEntry, crop)
        val target = range?.let { cropTarget(shape, it, sizePx) } ?: coverTarget(shape, sizePx)
        val image = when (val read = open.page(page.index, target, raw, Tiling.Never)) {
            is PageResult.Ready -> read.image
            else -> return read.made()
        }
        val bitmap = (image as PreparedImage.Whole).bitmap
        return try {
            withContext(decoding.dispatcher) { encode(bitmap, shape, direction, range, sizePx) }
        } catch (e: CancellationException) {
            bitmap.recycle()
            throw e
        }
    }

    private suspend fun page(open: OpenBook, entryIndex: Int, maxSidePx: Int): PagePreview {
        val page = open.pages.firstOrNull { it.entryIndex == entryIndex }
            ?: return if (open.indexError?.made() == Made.Unavailable) PagePreview.Unavailable else PagePreview.Missing
        val raw: RawPage = when (val read = open.raw(page.index)) {
            is PageResult.Ready -> read.image
            else -> return read.made().asPreview()
        }
        val shape = when (val read = open.shape(page.index, raw)) {
            is PageResult.Ready -> read.image
            else -> return read.made().asPreview()
        }
        return when (val read = open.page(page.index, PixelSize(maxSidePx, maxSidePx), raw, Tiling.Never)) {
            is PageResult.Ready -> PagePreview.Ready((read.image as PreparedImage.Whole).bitmap, shape)
            else -> read.made().asPreview()
        }
    }

    private fun Made.asPreview(): PagePreview = if (this == Made.Unreadable) PagePreview.Failed else PagePreview.Unavailable

    /** Recycles [source] and anything cut from it. */
    private fun encode(source: Bitmap, shape: PageShape.Known, direction: ReadingDirection, crop: CoverCrop?, sizePx: Int): Made {
        var shown = source
        try {
            if (crop != null) {
                val part = cropRect(crop, source.width, source.height)
                shown = Bitmap.createBitmap(source, part.left, part.top, part.width, part.height)
                shown = fitted(shown, source, sizePx)
            } else {
                coverCrop(shape, source.width, direction)?.let { half ->
                    shown = Bitmap.createBitmap(source, half.left, 0, half.width, source.height)
                }
            }
            val out = ByteArrayOutputStream(OUTPUT_HINT_BYTES)
            return if (shown.compress(WEBP, QUALITY, out)) Made.Webp(out.toByteArray()) else Made.Unavailable
        } catch (_: OutOfMemoryError) {
            return Made.Unavailable
        } finally {
            if (shown !== source) shown.recycle()
            source.recycle()
        }
    }

    /** [part] scaled down to [sizePx] on its long side when rounding the range outward left it a few pixels over. */
    private fun fitted(part: Bitmap, source: Bitmap, sizePx: Int): Bitmap {
        val longSide = maxOf(part.width, part.height)
        if (longSide <= sizePx) return part
        val scale = sizePx.toDouble() / longSide
        val width = if (part.width >= part.height) sizePx else (part.width * scale).roundToInt().coerceAtLeast(1)
        val height = if (part.height >= part.width) sizePx else (part.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(part, width, height, true)
        if (part !== source && part !== scaled) part.recycle()
        return scaled
    }

    private companion object {
        /** docs/spec.md section 5.4. */
        const val QUALITY = 80
        const val OUTPUT_HINT_BYTES = 32 * 1024

        // WEBP_LOSSY exists from API 30. Before it, WEBP with a quality below 100 is lossy as well.
        @Suppress("DEPRECATION")
        val WEBP: Bitmap.CompressFormat =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    }
}
