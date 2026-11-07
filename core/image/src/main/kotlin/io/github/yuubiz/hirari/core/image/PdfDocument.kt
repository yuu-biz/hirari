package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.LoadParams
import android.os.Build
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
import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * A PDF open for reading (docs/spec.md section 3.9). `PdfRenderer` is not thread-safe and opens one page at a
 * time, so every call to it runs on the one thread of [dispatcher].
 */
internal class PdfDocument private constructor(
    private val renderer: PdfRenderer,
    private val executor: ExecutorService,
    private val input: ProxyReads,
) : Closeable {
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    val pageCount: Int = renderer.pageCount

    /** The size of each page in points once a page was opened, touched only on [dispatcher]'s thread. */
    private val shapes = arrayOfNulls<PageShape.Known>(pageCount)

    /**
     * The size of the page at [index] in points, known without drawing it. Opening a page reads its objects, which
     * on a server are a request or more, so a page is opened for it only when it is wanted. Runs on the calling
     * thread, which must be [dispatcher]'s.
     */
    fun shape(index: Int): PageResult<PageShape.Known> = input.pageOperation {
        PageResult.Ready(shapes[index] ?: renderer.openPage(index).use(::shapeOf))
    }.also { result ->
        if (result is PageResult.Ready) shapes[index] = result.image
    }

    /**
     * The page at [index] drawn on white to fit [target], enlarged when it is smaller, and shrunk further when it
     * would take more than half of [budgetBytes]. Runs on the calling thread, which must be [dispatcher]'s.
     */
    fun render(index: Int, target: PixelSize, budgetBytes: Long): PageResult<PreparedImage> = input.pageOperation {
        renderer.openPage(index).use { page ->
            val shape = shapeOf(page)
            drawPdfPage(shape, target, budgetBytes) { size -> PageResult.Ready(PreparedImage.Whole(draw(page, size), shape)) }
        }
    }.also { result ->
        if (result is PageResult.Ready) shapes[index] = result.image.shape
    }

    /** The page at [index] as tiles drawn with a matrix (docs/spec.md section 3.9). Runs on [dispatcher]'s thread. */
    fun tiles(index: Int, decoding: ImageDecoding): PageResult<PreparedImage> = when (val shape = shape(index)) {
        is PageResult.Ready -> PageResult.Ready(
            PreparedImage.Tiled(
                PdfTiles(shape.image, decoding, dispatcher) { rect, sample -> renderTile(index, rect, sample, PDF_TILE_PIXELS_PER_POINT) },
                shape.image,
            ),
        )
        is PageResult.Failed -> shape
        is PageResult.Unsupported -> shape
    }

    /**
     * The part [rect] of the page at [index], planned as an image of [pixelsPerPoint] pixels a point, drawn on white
     * at 1/[sample] of that. Running out of memory is thrown, so that the caller reads the tile once more at a
     * smaller size (docs/spec.md sections 3.9 and 6.1). Runs on [dispatcher]'s thread.
     */
    fun renderTile(index: Int, rect: PixelRect, sample: Int, pixelsPerPoint: Int): PageResult<Bitmap> = input.pageOperation(rethrowOutOfMemory = true) {
        renderer.openPage(index).use { page ->
            val scale = pixelsPerPoint.toFloat() / sample
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(-rect.left.toFloat() / sample, -rect.top.toFloat() / sample)
            }
            PageResult.Ready(draw(page, PixelSize(sampled(rect.width, sample), sampled(rect.height, sample)), matrix))
        }
    }

    private fun shapeOf(page: PdfRenderer.Page) = PageShape.Known(page.width, page.height)

    /** [page] on white in a bitmap of [size], fitted to it, or placed by [transform] from points when one is given. */
    private fun draw(page: PdfRenderer.Page, size: PixelSize, transform: Matrix? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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
        suspend fun open(source: ByteSource, storage: StorageManager, password: CharArray? = null): Opened {
            val executor = Executors.newSingleThreadExecutor { Thread(it, "pdf") }
            val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
            var renderer: PdfRenderer? = null
            val input = ProxyReads(source)
            // Until the proxy descriptor exists, closing the source is up to this function, then to the descriptor.
            var proxied = false
            try {
                return withContext(dispatcher) {
                    input.beginOperation()
                    val descriptor = try {
                        storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, input, proxyHandler)
                    } catch (e: IOException) {
                        source.close()
                        throw e
                    }
                    proxied = true
                    renderer = try {
                        if (Build.VERSION.SDK_INT >= 35) {
                            val params = LoadParams.Builder().apply {
                                if (password != null) setPassword(password.concatToString())
                            }.build()
                            PdfRenderer(descriptor, params)
                        } else PdfRenderer(descriptor)
                    } catch (e: Throwable) {
                        descriptor.close()
                        throw e
                    }
                    val document = PdfDocument(renderer!!, executor, input)
                    if (input.operationFailure() != null) throw IOException("PDF input failed while opening")
                    Opened.Document(document)
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
                    is OutOfMemoryError -> Opened.Failed(BookFailure.ReadError)
                    is Exception -> Opened.Failed(input.openFailure(e).let { if (it == BookFailure.Encrypted && Build.VERSION.SDK_INT >= 35) BookFailure.PasswordRequired else it })
                    else -> throw e
                }
            }
        }

        /** The proxy descriptors call back on this thread. It must not be a session thread, which waits on them. */
        private val proxyHandler: Handler by lazy { Handler(HandlerThread("pdf-proxy").apply { start() }.looper) }
    }
}

/** Serves the reads of a proxy descriptor from [source], and closes it when the descriptor is released. */
internal class ProxyReads(private val source: ByteSource) : ProxyFileDescriptorCallback() {
    // Callbacks record the first failure on the proxy thread; the PDF thread resets and checks it per operation.
    private val failure = AtomicReference<IOException?>()

    fun beginOperation() = failure.set(null)

    fun operationFailure(): PageResult.Failed? = when (failure.get()) {
        is ConnectionLostException -> connectionLost
        null -> null
        else -> readError
    }

    /** A renderer may return normally after a proxy read failed, so check both return and exception paths. */
    fun <T> pageOperation(rethrowOutOfMemory: Boolean = false, operation: () -> PageResult<T>): PageResult<T> {
        beginOperation()
        val result = try {
            operation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            operationFailure()?.let { return it }
            if (rethrowOutOfMemory) throw e
            return readError
        } catch (e: Exception) {
            return pageFailure(e)
        }
        val failed = operationFailure() ?: return result
        if (result is PageResult.Ready) releaseUnwanted(result.image)
        return failed
    }

    override fun onGetSize(): Long = try {
        source.size
    } catch (e: IOException) {
        failure.compareAndSet(null, e)
        throw ErrnoException("getSize", OsConstants.EIO, e)
    }

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        val buffer = ByteBuffer.wrap(data, 0, size)
        try {
            while (buffer.hasRemaining() && source.read(offset + buffer.position(), buffer) > 0) Unit
        } catch (e: IOException) {
            failure.compareAndSet(null, e)
            throw ErrnoException("read", OsConstants.EIO, e)
        }
        return buffer.position()
    }

    /** The input error takes precedence over the renderer's rejection of the missing data (section 3.9). */
    fun pageFailure(error: Exception): PageResult.Failed = operationFailure() ?: when (error) {
        is RuntimeException, is IOException -> corrupt
        else -> readError
    }

    fun openFailure(error: Exception): BookFailure = if (failure.get() != null) BookFailure.ReadError else when (error) {
        is SecurityException -> BookFailure.Encrypted
        is IOException -> BookFailure.Corrupt
        else -> BookFailure.ReadError
    }

    override fun onRelease() = source.close()
}

/**
 * The pixels a point of the grid the tiles of a PDF page are planned on (docs/spec.md section 3.9). The sample size
 * of a plan sets how finely a tile is drawn, so a page reads sharp up to this many pixels a point.
 */
internal const val PDF_TILE_PIXELS_PER_POINT: Int = 64

/**
 * The tiles of a PDF page of [shape], planned as an image of [PDF_TILE_PIXELS_PER_POINT] pixels a point, each drawn
 * for the part it covers by [render] on [dispatcher], the document's thread. [render] throws when memory runs out and
 * otherwise gives the failure it had, so that a lost connection stays retryable. Closing the tiles leaves the
 * document open.
 */
internal class PdfTiles(
    shape: PageShape.Known,
    decoding: ImageDecoding,
    dispatcher: CoroutineDispatcher,
    private val render: (rect: PixelRect, sample: Int) -> PageResult<Bitmap>,
) : CachedTiles(PixelSize(shape.width * PDF_TILE_PIXELS_PER_POINT, shape.height * PDF_TILE_PIXELS_PER_POINT), decoding, dispatcher) {
    override fun decodeTile(rect: PixelRect, sample: Int, reduction: Int): PageResult<Bitmap> = render(rect, sample * reduction)

    override fun release() = Unit
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
