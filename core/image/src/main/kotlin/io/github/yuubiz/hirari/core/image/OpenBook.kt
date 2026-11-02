package io.github.yuubiz.hirari.core.image

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

/**
 * A book open for reading (docs/spec.md sections 2.4 and 3.7). Every call returns a [PageResult]. Only
 * [CancellationException] and virtual machine errors other than [OutOfMemoryError] get through. Calls may
 * overlap; an archive serializes its reads in its session.
 *
 * @property pages the page list in natural order, fixed while the book is open (section 3.5).
 * @property indexError why listing the archive stopped early, or null when it reached the end.
 */
class OpenBook internal constructor(
    private val bytes: PageBytes,
    val pages: List<PageEntry>,
    val indexError: FailureReason?,
    private val decoding: ImageDecoding,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob())

    /** The page at [index] scaled to fit [target], in pixels. */
    suspend fun page(index: Int, target: PixelSize): PageResult<PreparedImage> =
        withBytes(index) { data, isActive -> decodePage(data, target, decoding.budgetBytes, isActive) }

    /** The size of the page at [index], from the image header alone (docs/spec.md sections 2.5 and 5.3). */
    suspend fun shape(index: Int): PageResult<PageShape.Known> = withBytes(index) { data, _ -> decodeShape(data) }

    /** Stops the decodes in progress at their next check. A decode past its header finishes and is dropped. */
    override fun close() {
        scope.cancel()
        bytes.close()
    }

    /** [decode] gets the bytes and whether the result is still wanted. */
    private suspend fun <T> withBytes(index: Int, decode: (ByteArray, () -> Boolean) -> PageResult<T>): PageResult<T> = try {
        when (val read = bytes.read(pages[index])) {
            is PageResult.Ready -> decodeWithTimeout(scope, decoding.dispatcher) { isActive -> decode(read.image, isActive) }
            is PageResult.Failed -> read
            is PageResult.Unsupported -> read
        }
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
        readError
    } catch (_: Exception) {
        readError
    } catch (_: OutOfMemoryError) {
        readError
    }
}

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
