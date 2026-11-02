package io.github.yuubiz.hirari.core.image.thumbnail

import android.graphics.Bitmap
import android.os.Build
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.image.PreparedImage
import io.github.yuubiz.hirari.core.image.RawPage
import io.github.yuubiz.hirari.core.image.Tiling
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReadingDirection
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException

/** Makes the WebP of one cover. The orchestrator in [Thumbnails] calls it with a lane's permit held. */
internal fun interface ThumbnailMaker {
    suspend fun make(book: ThumbSubject.Book, sizePx: Int, direction: ReadingDirection): Made
}

/**
 * Opens the book, decodes the cover page at the size of the thumbnail through the plan of docs/spec.md section
 * 3.7, cuts the front half from a wide cover, and encodes it. [decoding] is the instance of the thumbnails, so
 * its dispatcher is not the reader's.
 */
internal class BookThumbnailMaker(private val decoding: ImageDecoding) : ThumbnailMaker {
    override suspend fun make(book: ThumbSubject.Book, sizePx: Int, direction: ReadingDirection): Made {
        val opened = try {
            book.open(decoding)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Made.Unavailable
        }
        val open = when (opened) {
            is BookResult.Failed -> return opened.reason.made()
            is BookResult.Opened -> opened.book
        }
        return open.use { cover(it, book.coverEntry, sizePx, direction) }
    }

    private suspend fun cover(open: OpenBook, coverEntry: Int?, sizePx: Int, direction: ReadingDirection): Made {
        val page = coverPage(open.pages, coverEntry) ?: return Made.Unreadable
        val raw: RawPage = when (val read = open.raw(page.index)) {
            is PageResult.Ready -> read.image
            else -> return read.made()
        }
        val shape = when (val read = open.shape(page.index, raw)) {
            is PageResult.Ready -> read.image
            else -> return read.made()
        }
        val image = when (val read = open.page(page.index, coverTarget(shape, sizePx), raw, Tiling.Never)) {
            is PageResult.Ready -> read.image
            else -> return read.made()
        }
        val bitmap = (image as PreparedImage.Whole).bitmap
        return try {
            withContext(decoding.dispatcher) { encode(bitmap, shape, direction) }
        } catch (e: CancellationException) {
            bitmap.recycle()
            throw e
        }
    }

    /** Recycles [source] and anything cut from it. */
    private fun encode(source: Bitmap, shape: PageShape.Known, direction: ReadingDirection): Made {
        var shown = source
        try {
            coverCrop(shape, source.width, direction)?.let { crop ->
                shown = Bitmap.createBitmap(source, crop.left, 0, crop.width, source.height)
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
