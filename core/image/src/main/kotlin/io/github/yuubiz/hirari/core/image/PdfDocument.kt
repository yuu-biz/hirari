package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.source.ByteSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * A PDF open for reading (docs/spec.md section 3.9). `PdfRenderer` is not thread-safe and opens one page at a
 * time, so every call to it runs on the one thread of [dispatcher].
 */
internal class PdfDocument private constructor(
    private val renderer: PdfRenderer,
    private val executor: ExecutorService,
    /** The size of each page in points, known without drawing it. */
    val shapes: List<PageShape.Known>,
) : Closeable {
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /**
     * The page at [index] drawn on white to fit [target], enlarged when it is smaller, and shrunk further when it
     * would take more than half of [budgetBytes]. Runs on the calling thread, which must be [dispatcher]'s.
     */
    fun render(index: Int, target: PixelSize, budgetBytes: Long): PageResult<PreparedImage> = renderer.openPage(index).use { page ->
        val shape = shapes[index]
        drawPdfPage(shape, target, budgetBytes) { size -> PageResult.Ready(PreparedImage.Whole(draw(page, size), shape)) }
    }

    private fun draw(page: PdfRenderer.Page, size: PixelSize): Bitmap {
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap.prepareToDraw()
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
        return bitmap
    }

    /** Closes the renderer on its thread after the calls already queued, which closes the document. */
    override fun close() {
        executor.execute { renderer.close() }
        executor.shutdown()
    }

    sealed interface Opened {
        class Document(val document: PdfDocument) : Opened

        data class Failed(val reason: BookFailure) : Opened
    }

    companion object {
        /**
         * Opens the PDF in [source], which the document owns from here on. `PdfRenderer` needs a seekable
         * descriptor, and a [ByteSource] hides its own, so the renderer reads through a proxy descriptor that
         * [storage] serves from [source] (docs/spec.md section 3.9).
         */
        suspend fun open(source: ByteSource, storage: StorageManager): Opened {
            val executor = Executors.newSingleThreadExecutor { Thread(it, "pdf") }
            val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
            var renderer: PdfRenderer? = null
            // Until the proxy descriptor exists, closing the source is up to this function, then to the descriptor.
            var proxied = false
            try {
                return withContext(dispatcher) {
                    val descriptor = try {
                        storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, ProxyReads(source), proxyHandler)
                    } catch (e: IOException) {
                        source.close()
                        throw e
                    }
                    proxied = true
                    renderer = try {
                        PdfRenderer(descriptor)
                    } catch (e: Throwable) {
                        descriptor.close()
                        throw e
                    }
                    val opened = renderer!!
                    val shapes = (0 until opened.pageCount).map { i -> opened.openPage(i).use { PageShape.Known(it.width, it.height) } }
                    Opened.Document(PdfDocument(opened, executor, shapes))
                }
            } catch (e: CancellationException) {
                if (!proxied) source.close()
                renderer?.let { executor.execute { it.close() } }
                executor.shutdown()
                throw e
            } catch (e: Throwable) {
                renderer?.let { executor.execute { it.close() } }
                executor.shutdown()
                return when (e) {
                    is SecurityException -> Opened.Failed(BookFailure.Encrypted)
                    is OutOfMemoryError -> Opened.Failed(BookFailure.ReadError)
                    is IOException -> Opened.Failed(BookFailure.Corrupt)
                    is Exception -> Opened.Failed(BookFailure.ReadError)
                    else -> throw e
                }
            }
        }

        /** The proxy descriptors call back on this thread. It must not be a session thread, which waits on them. */
        private val proxyHandler: Handler by lazy { Handler(HandlerThread("pdf-proxy").apply { start() }.looper) }
    }
}

/** Serves the reads of a proxy descriptor from [source], and closes it when the descriptor is released. */
private class ProxyReads(private val source: ByteSource) : ProxyFileDescriptorCallback() {
    override fun onGetSize(): Long = source.size

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        val buffer = ByteBuffer.wrap(data, 0, size)
        try {
            while (buffer.hasRemaining() && source.read(offset + buffer.position(), buffer) > 0) Unit
        } catch (e: IOException) {
            throw ErrnoException("read", OsConstants.EIO, e)
        }
        return buffer.position()
    }

    override fun onRelease() = source.close()
}

/**
 * Draws a PDF page of [shape] with [draw], at the size that fits [target], enlarged when it is smaller, and
 * smaller when it would take more than half of [budgetBytes]. When memory runs out it draws once more at half
 * that size on each side, and fails with a read error if that runs out too (docs/spec.md section 6.1).
 */
internal fun <T> drawPdfPage(
    shape: PageShape.Known,
    target: PixelSize,
    budgetBytes: Long,
    draw: (PixelSize) -> PageResult<T>,
): PageResult<T> {
    var scale = minOf(target.width / shape.width.toDouble(), target.height / shape.height.toDouble())
    val pixels = shape.width * scale * shape.height * scale
    if (pixels * 4 > budgetBytes / 2) scale *= Math.sqrt(budgetBytes / 2 / 4 / pixels)
    val size = PixelSize((shape.width * scale).roundToInt().coerceAtLeast(1), (shape.height * scale).roundToInt().coerceAtLeast(1))
    return retryingOnce { reduction ->
        draw(PixelSize((size.width / reduction).coerceAtLeast(1), (size.height / reduction).coerceAtLeast(1)))
    }
}
