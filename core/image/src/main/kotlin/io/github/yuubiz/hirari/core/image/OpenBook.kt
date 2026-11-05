package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.EntryOrder
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.ImageType
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/** Where the bytes of the pages of an open book come from: an archive session, or the files of a folder. */
internal interface PageBytes : Closeable {
    fun prioritize(entries: List<Int>) = Unit
    suspend fun prefix(page: PageEntry, count: Int): PageResult<ByteArray> = read(page)
    suspend fun invalidate(page: PageEntry) = Unit
    fun fromStash(bytes: ByteArray): Boolean = false

    /** The bytes of [page], as [PageResult.Ready], or why they cannot be read. */
    suspend fun read(page: PageEntry): PageResult<ByteArray>
}

/** What the pages of an open book are. */
internal sealed interface BookContent : Closeable {
    /** Encoded images, read from an archive or the files of a folder. */
    class Images(source: PageBytes) : BookContent {
        val bytes: PageBytes = if (source is StashedPages) source else OrderedPages(source)
        override fun close() = bytes.close()
    }

    /** The pages of a PDF, drawn by its document session. */
    class Pdf(val document: PdfDocument) : BookContent {
        override fun close() = document.close()
    }
}

/**
 * A book open for reading (docs/spec.md sections 2.4 and 3.7). Every call returns a [PageResult]. Only
 * [CancellationException] and virtual machine errors other than [OutOfMemoryError] get through. Calls may
 * overlap; an archive serializes its reads in its session, and a PDF its drawing in its document session.
 *
 * @property pages the page list in natural order, or in another after [ordered], fixed while the book is open (section 3.5).
 * @property indexError why listing the archive stopped early, or null when it reached the end.
 * @property container what the book was opened from, for the index of opened books (section 2.2).
 */
class OpenBook internal constructor(
    private val content: BookContent,
    val container: Container,
    val pages: List<PageEntry>,
    val indexError: FailureReason?,
    private val decoding: ImageDecoding,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob())

    /** The SMB prefetch layer of an archive on a server (docs/spec.md section 5.3), or `null` for a book without one. */
    val stash: PageStash? = ((content as? BookContent.Images)?.bytes as? StashedPages)?.let { stashed ->
        object : PageStash {
            override suspend fun holds(index: Int): Boolean = stashed.holds(pages[index])

            override suspend fun canFill(index: Int): Boolean = stashed.canFill(pages[index])

            override suspend fun fill(index: Int): Fill = stashed.fill(pages[index])
        }
    }

    /**
     * This book with its pages in [order] (docs/spec.md section 3.5). A folder keeps the natural order under
     * 格納順, since the order its provider lists the files in means nothing. The result takes over what this book
     * reads from; call it right after opening, and use only the result.
     */
    fun ordered(order: PageOrder): OpenBook {
        val own = if (container == Container.Folder) order.copy(entries = EntryOrder.Natural) else order
        return OpenBook(content, container, sortedPages(pages, own), indexError, decoding)
    }

    suspend fun invalidate(index: Int) { (content as? BookContent.Images)?.bytes?.invalidate(pages[index]) }

    fun fromStash(raw: RawPage): Boolean = raw is RawPage.Encoded &&
        (content as? BookContent.Images)?.bytes?.fromStash(raw.bytes) == true

    /** The page order for every queued read of this book, including shape and disk-stash reads. */
    fun prioritize(pagesInOrder: List<Int>) {
        (content as? BookContent.Images)?.bytes?.prioritize(pagesInOrder.map { pages[it].entryIndex })
    }

    /**
     * The encoded bytes of the page at [index], for the raw-data prefetch layer to hold (docs/spec.md section
     * 5.3). [page] and [shape] decode from them without reading the book again. A PDF page has none to hold.
     */
    suspend fun raw(index: Int): RawResult = guarded {
        when (content) {
            is BookContent.Pdf -> PageResult.Ready(RawPage.Drawn)
            is BookContent.Images -> when (val read = content.bytes.read(pages[index])) {
                is PageResult.Ready -> PageResult.Ready(RawPage.Encoded(read.image))
                is PageResult.Failed -> read
                is PageResult.Unsupported -> read
            }
        }
    }

    /**
     * The page at [index] scaled to fit [target], in pixels, decoded from [raw] when it is given. With
     * [Tiling.AsPlanned] an image narrower than 1:3 comes as [PreparedImage.Tiled] (docs/spec.md section 3.7).
     * A PDF page is drawn to fit [target], enlarged when it is smaller (section 3.9).
     */
    suspend fun page(
        index: Int,
        target: PixelSize,
        raw: RawPage? = null,
        tiling: Tiling = Tiling.Never,
    ): PageResult<PreparedImage> = when (content) {
        is BookContent.Pdf -> guarded {
            decodeWithTimeout(scope, content.document.dispatcher) { _ ->
                try {
                    content.document.render(pages[index].entryIndex, target, decoding.budgetBytes)
                } catch (_: OutOfMemoryError) {
                    readError
                } catch (_: RuntimeException) {
                    corrupt
                }
            }
        }
        is BookContent.Images -> withBytes(content, index, raw) { data, isActive ->
            decodePage(data, target, decoding, tiling, isActive)
        }
    }

    /** The size of the page at [index], from the image header alone (docs/spec.md sections 2.5 and 5.3). */
    suspend fun shape(index: Int, raw: RawPage? = null): PageResult<PageShape.Known> = when (content) {
        is BookContent.Pdf -> guarded { PageResult.Ready(content.document.shape(pages[index].entryIndex)) }
        is BookContent.Images -> if (raw != null) withBytes(content, index, raw) { data, _ -> decodeShape(data) }
        else guarded {
            probeShape(content.bytes, pages[index]) { data ->
                decodeWithTimeout(scope, decoding.dispatcher) { decodeShape(data) }
            }
        }
    }

    /** Stops the decodes in progress at their next check. A decode past its header finishes and is dropped. */
    override fun close() {
        scope.cancel()
        content.close()
    }

    /** [decode] gets the bytes, from [raw] or read from [images], and whether the result is still wanted. */
    private suspend fun <T> withBytes(
        images: BookContent.Images,
        index: Int,
        raw: RawPage?,
        decode: (ByteArray, () -> Boolean) -> PageResult<T>,
    ): PageResult<T> = guarded {
        val data = (raw as? RawPage.Encoded)?.bytes ?: when (val read = images.bytes.read(pages[index])) {
            is PageResult.Ready -> read.image
            is PageResult.Failed -> return@guarded read
            is PageResult.Unsupported -> return@guarded read
        }
        val result = decodeWithTimeout(scope, decoding.dispatcher) { isActive -> decode(data, isActive) }
        if (result !is PageResult.Ready && raw == null && images.bytes.fromStash(data)) {
            images.bytes.invalidate(pages[index])
            when (val fresh = images.bytes.read(pages[index])) {
                is PageResult.Ready -> decodeWithTimeout(scope, decoding.dispatcher) { isActive -> decode(fresh.image, isActive) }
                is PageResult.Failed -> fresh
                is PageResult.Unsupported -> fresh
            }
        } else result
    }

    private suspend inline fun <T> guarded(block: () -> PageResult<T>): PageResult<T> = try {
        block()
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
        readError
    } catch (_: Exception) {
        readError
    } catch (_: OutOfMemoryError) {
        readError
    }
}

/** Bounded probes share the page reader's queue. Only an inconclusive header needs the full entry. */
internal suspend fun probeShape(
    bytes: PageBytes,
    page: PageEntry,
    decode: suspend (ByteArray) -> PageResult<PageShape.Known>,
): PageResult<PageShape.Known> {
    for (limit in listOf(64 * 1024, 128 * 1024)) {
        when (val read = bytes.prefix(page, limit)) {
            is PageResult.Ready -> {
                val shape = decode(read.image)
                if (shape is PageResult.Ready || read.image.size < limit) return shape
            }
            is PageResult.Failed -> return read
            is PageResult.Unsupported -> return read
        }
    }
    return when (val read = bytes.read(page)) {
        is PageResult.Ready -> {
            val result = decode(read.image)
            if (result !is PageResult.Ready && bytes.fromStash(read.image)) {
                bytes.invalidate(page)
                when (val fresh = bytes.read(page)) {
                    is PageResult.Ready -> decode(fresh.image)
                    is PageResult.Failed -> fresh
                    is PageResult.Unsupported -> fresh
                }
            } else result
        }
        is PageResult.Failed -> read
        is PageResult.Unsupported -> read
    }
}

/** The data of a page as the raw-data prefetch layer holds it (docs/spec.md section 5.3). */
sealed interface RawPage {
    /** What holding it takes from the memory budget. */
    val byteCount: Long

    /** The bytes of an image entry or file. */
    class Encoded internal constructor(internal val bytes: ByteArray) : RawPage {
        override val byteCount: Long get() = bytes.size.toLong()

        /** The image type the first bytes show, or `null` when they show none (docs/spec.md section 3.2). */
        val type: ImageType? get() = sniffImage(bytes)

        /** Writes the bytes as the book holds them, as when the page is shared (docs/spec.md section 4.8). */
        fun writeTo(out: OutputStream) = out.write(bytes)
    }

    /** A page drawn from the open document, which has no bytes of its own to hold. */
    data object Drawn : RawPage {
        override val byteCount: Long get() = 0
    }
}

typealias RawResult = PageResult<RawPage>

/**
 * Runs [decode] in [scope] on [dispatcher] and gives up waiting after the timeout of docs/spec.md section 3.7.
 * A decode still queued by then never starts. One already running is told through its `isActive` that its
 * result is not wanted. Inside `ImageDecoder` it cannot stop, so it keeps its thread until it ends, and its
 * result is released with [release] when it arrives, because nobody else holds it.
 */
internal suspend fun <T> decodeWithTimeout(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    release: (T) -> Unit = ::releaseUnwanted,
    decode: (() -> Boolean) -> PageResult<T>,
): PageResult<T> {
    val caller = currentCoroutineContext()
    val unclaimed = Unclaimed(release)
    val work = scope.async(dispatcher) { decode { isActive && caller.isActive }.also(unclaimed::offer) }
    var delivered: PageResult<T>? = null
    return try {
        (withTimeoutOrNull(ImageDecoding.DECODE_TIMEOUT_MILLIS) { work.await() } ?: timedOut).also { delivered = it }
    } finally {
        work.cancel()
        unclaimed.abandon(delivered)
    }
}

/** What a decode that nobody waits for any more holds: bitmaps are recycled and tile sources closed. */
internal fun releaseUnwanted(image: Any?) {
    when (image) {
        is Bitmap -> image.recycle()
        is PreparedImage.Whole -> image.bitmap.recycle()
        is PreparedImage.Tiled -> image.source.close()
    }
}

/**
 * The result of a decode, from the thread that made it to the caller that waits. A cancelled `async` drops its
 * value, so the result passes through here: released when it comes after the caller gave up, or when the caller
 * gave up in the instant it came.
 */
private class Unclaimed<T>(private val release: (T) -> Unit) {
    private var abandoned = false
    private var held: PageResult<T>? = null

    fun offer(result: PageResult<T>) {
        val late = synchronized(this) {
            if (!abandoned) held = result
            abandoned
        }
        if (late) discard(result)
    }

    fun abandon(delivered: PageResult<T>?) {
        val left = synchronized(this) {
            abandoned = true
            held.also { held = null }
        }
        if (left != null && left !== delivered) discard(left)
    }

    private fun discard(result: PageResult<T>) {
        if (result is PageResult.Ready) release(result.image)
    }
}
