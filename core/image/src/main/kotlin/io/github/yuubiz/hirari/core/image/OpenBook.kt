package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.FailureReason
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
import kotlin.coroutines.cancellation.CancellationException

/** Where the bytes of the pages of an open book come from: an archive session, or the files of a folder. */
internal interface PageBytes : Closeable {
    /** The bytes of [page], as [PageResult.Ready], or why they cannot be read. */
    suspend fun read(page: PageEntry): PageResult<ByteArray>
}

/** What the pages of an open book are. */
internal sealed interface BookContent : Closeable {
    /** Encoded images, read from an archive or the files of a folder. */
    class Images(val bytes: PageBytes) : BookContent {
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
 * @property pages the page list in natural order, fixed while the book is open (section 3.5).
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
                    content.document.render(index, target, decoding.budgetBytes)
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
        is BookContent.Pdf -> PageResult.Ready(content.document.shapes[index])
        is BookContent.Images -> withBytes(content, index, raw) { data, _ -> decodeShape(data) }
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
        decodeWithTimeout(scope, decoding.dispatcher) { isActive -> decode(data, isActive) }
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

/** The data of a page as the raw-data prefetch layer holds it (docs/spec.md section 5.3). */
sealed interface RawPage {
    /** What holding it takes from the memory budget. */
    val byteCount: Long

    /** The bytes of an image entry or file. */
    class Encoded internal constructor(internal val bytes: ByteArray) : RawPage {
        override val byteCount: Long get() = bytes.size.toLong()
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
 * result is dropped.
 */
internal suspend fun <T> decodeWithTimeout(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    decode: (() -> Boolean) -> PageResult<T>,
): PageResult<T> {
    val caller = currentCoroutineContext()
    val work = scope.async(dispatcher) { decode { isActive && caller.isActive } }
    return try {
        withTimeoutOrNull(ImageDecoding.DECODE_TIMEOUT_MILLIS) { work.await() } ?: timedOut
    } finally {
        work.cancel()
    }
}
