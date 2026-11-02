package io.github.yuubiz.hirari.feature.reader

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.data.ProgressWriter
import io.github.yuubiz.hirari.core.data.Storage
import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.Books
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.PreparedImage
import io.github.yuubiz.hirari.core.model.BOOK_ID_PREFIX_BYTES
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.documentBookId
import io.github.yuubiz.hirari.core.model.folderBookId
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.NoRoomForCopyException
import io.github.yuubiz.hirari.core.source.documentInfo
import io.github.yuubiz.hirari.core.source.openByteSource
import io.github.yuubiz.hirari.feature.reader.prefetch.Prefetcher
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchDepths
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchInput
import io.github.yuubiz.hirari.feature.reader.prefetch.shownViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/** What the app shares with every reader: one of each per process. */
class ReaderServices(
    val storage: Storage,
    val progress: ProgressWriter,
    val decoding: ImageDecoding,
    /** Outlives every screen, on `Dispatchers.IO`. */
    val appScope: CoroutineScope,
)

/** A book picked through SAF: one document, or the image files of one folder tree. */
sealed interface BookRef {
    val uri: Uri

    /**
     * The book's path for the page bar: the last segment of its URI, which a device's storage gives as
     * `primary:Manga/Book.zip` (docs/spec.md section 4.8, ページバー).
     */
    val path: String get() = uri.lastPathSegment ?: uri.toString()

    data class Document(override val uri: Uri) : BookRef

    data class Folder(override val uri: Uri) : BookRef
}

sealed interface ReaderPhase {
    data object Opening : ReaderPhase

    data class Failed(val reason: BookFailure) : ReaderPhase

    /** The book opened but holds no image. */
    data object NoPages : ReaderPhase

    data object Reading : ReaderPhase
}

/**
 * One open book in the reader. It owns the [OpenBook] and its [Prefetcher], and turns every input into a
 * [ReaderEvent] for [ReaderState]. Everything here runs on the main thread; the book, the database, and the
 * decoder move their work off it.
 */
class ReaderViewModel(
    private val application: Application,
    private val services: Deferred<ReaderServices>,
    private val ref: BookRef,
) : ViewModel() {
    var phase: ReaderPhase by mutableStateOf(ReaderPhase.Opening)
        private set

    var state: ReaderState? by mutableStateOf(null)
        private set

    /** The requested orientation of the `画面の向き` command, which outlives the activity turning. */
    var orientation: Int by mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        private set

    /** Changes whenever a view is prepared or dropped, or a page fails. */
    var prepared: Int by mutableIntStateOf(0)
        private set

    private var opened: Opened? = null

    /** Holds the book from the moment it opens, so leaving the screen closes it whatever the open was waiting on. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val stops = StopRelay()
    private val bookOwner = Ownership<OpenBook> { book -> services.getCompleted().appScope.launch { book.close() } }
    private var viewport: Viewport? = null

    /** While the page panel's thumbnail strip is up, prefetch loads only the current view (docs/spec.md section 5.3). */
    private var stripShown = false

    /**
     * Small decodes for the page panel and the page bar. Evicted ones are left to the collector, not recycled,
     * since a strip cell may still draw one for a frame.
     */
    private val thumbnails = object : LinkedHashMap<Int, Bitmap>(THUMBNAILS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>) = size > THUMBNAILS
    }

    private class Opened(
        val book: OpenBook,
        val id: BookId,
        val start: Int,
        val config: ReaderConfig,
        val prefetcher: Prefetcher<Bitmap>,
        val services: ReaderServices,
        val progress: ProgressLog,
    )

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) opened?.prefetcher?.trim()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    init {
        application.registerComponentCallbacks(trimCallbacks)
        viewModelScope.launch { open() }
    }

    val pageNames: List<String> get() = opened?.book?.pages?.map { it.name }.orEmpty()

    val bookPath: String get() = ref.path

    fun showStrip(shown: Boolean) {
        if (stripShown == shown) return
        stripShown = shown
        val current = state ?: return
        opened?.prefetcher?.update(current.prefetchInput(paused = shown))
    }

    fun cachedThumbnail(page: Int): Bitmap? = thumbnails[page]

    /** [page] fitted into [size], or `null` for a page that does not decode. */
    suspend fun thumbnail(page: Int, size: PixelSize): Bitmap? {
        thumbnails[page]?.let { return it }
        val book = opened?.book ?: return null
        val image = (book.page(page, size) as? PageResult.Ready)?.image as? PreparedImage.Whole ?: return null
        return image.bitmap.also { thumbnails[page] = it }
    }

    /** 位置を戻して閉じる: the book gets back the position and state it had when it opened. The screen closes next. */
    fun restorePosition() {
        val book = opened ?: return
        book.services.progress.submit(book.progress.restored(Instant.now()))
    }

    fun preparedView(view: PageView) = opened?.prefetcher?.get(view)

    val failedPages: Map<Int, PageResult<Nothing>> get() = opened?.prefetcher?.failedPages.orEmpty()

    /** Every page failed, so the book shows one error for all of it (docs/spec.md section 4.8, ページのエラー表示). */
    val allPagesFailed: Boolean
        get() = prepared.let { opened?.let { it.prefetcher.failedPages.size == it.book.pages.size } == true }

    fun onViewport(next: Viewport) {
        viewport = next
        val current = state
        if (current != null) dispatch(ReaderEvent.ViewportChanged(next)) else start()
    }

    fun dispatch(event: ReaderEvent) {
        val before = state ?: return
        val after = before.reduce(event)
        if (after == before) return
        state = after
        changed(before, after)
    }

    fun act(action: ReaderAction, nowMs: Long) = dispatch(ReaderEvent.Act(action, nowMs))

    /** The `画面の向き` command: follow the device, then portrait, then landscape. */
    fun nextOrientation() {
        orientation = when (orientation) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun retry(page: Int) {
        opened?.prefetcher?.retry(page)
    }

    /** Runs a command of the command bar, or returns false for one of a later milestone. */
    fun command(command: ReaderCommand, nowMs: Long): Boolean {
        val current = state ?: return false
        when (val effect = current.config.effectOf(command)) {
            is CommandEffect.Configure -> dispatch(ReaderEvent.ConfigChanged(effect.config))
            CommandEffect.ShiftForward -> act(ReaderAction.Shift(forward = true), nowMs)
            CommandEffect.NextOrientation -> nextOrientation()
            CommandEffect.NotYet -> return false
        }
        return true
    }

    /** The reader left the screen. The process may end before [onCleared], so the history is written now. */
    fun onStop() {
        stops.stop()
    }

    override fun onCleared() {
        application.unregisterComponentCallbacks(trimCallbacks)
        thumbnails.clear()
        opened?.let {
            it.prefetcher.close()
            it.services.progress.flush()
        }
        stops.stop()
        bookOwner.release()
    }

    private suspend fun open() {
        val services = services.await()
        val copyDir = withContext(Dispatchers.IO) { File(application.cacheDir, "copy") }
        val settings = services.storage.settings.settings.first()
        val config = ReaderConfig.of(settings)
        val (id, result) = withContext(Dispatchers.IO) {
            openBook(services, copyDir).also { (_, result) -> (result as? BookResult.Opened)?.let { bookOwner.take(it.book) } }
        }
        val book = when (result) {
            is BookResult.Failed -> {
                phase = ReaderPhase.Failed(result.reason)
                return
            }
            is BookResult.Opened -> result.book
        }
        if (book.pages.isEmpty()) {
            bookOwner.release()
            phase = ReaderPhase.NoPages
            return
        }
        // A reader closed moments ago may still hold this book's latest position in the writer's window.
        val openedAt = Instant.now()
        services.progress.flush().join()
        val saved = services.storage.progress.get(id)
        val indexed = withContext(Dispatchers.IO) { describe(id, book) }
        val start = (saved?.position as? ReadingPosition.Image)?.viewStart?.coerceIn(book.pages.indices) ?: 0
        val lowRam = application.getSystemService(ActivityManager::class.java).isLowRamDevice
        val listener = object : Prefetcher.Listener {
            override fun onShape(page: Int, shape: PageShape.Known) = dispatch(ReaderEvent.ShapeLearned(page, shape))

            override fun onChanged() {
                prepared++
            }
        }
        val loader = BookPageLoader(book, services.decoding.dispatcher)
        val prefetcher = Prefetcher(viewModelScope, loader, ::recycleAfterFrames, services.decoding.budgetBytes, prefetchDepths(lowRam), listener)
        val session = ReadingSession(indexed, openedAt, settings.historyLimit, services.storage.books, services.storage.history, services.appScope)
        opened = Opened(book, id, start, config, prefetcher, services, ProgressLog(id, book.pages.size, saved))
        stops.attach(session)
        phase = ReaderPhase.Reading
        start()
    }

    /**
     * The index entry of the opened [book] (docs/spec.md section 2.2). What the provider does not report stays
     * unknown: no name falls back to the path, no size to 0, and no modification time to the epoch.
     */
    private suspend fun describe(id: BookId, book: OpenBook): Book {
        val documentUri = when (ref) {
            is BookRef.Document -> ref.uri
            is BookRef.Folder -> DocumentsContract.buildDocumentUriUsingTree(ref.uri, DocumentsContract.getTreeDocumentId(ref.uri))
        }
        val info = try {
            application.contentResolver.documentInfo(documentUri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val size = when (ref) {
            is BookRef.Document -> info?.size ?: 0L
            is BookRef.Folder -> book.pages.sumOf { it.declaredSize ?: 0L }
        }
        return Book(
            id = id,
            source = BookSource.External(UriString(ref.uri.toString())),
            format = BookFormat.Images(book.container),
            title = info?.name ?: ref.path,
            byteSize = size,
            modified = info?.lastModifiedMs?.let(Instant::ofEpochMilli) ?: Instant.EPOCH,
            pageCount = book.pages.size,
        )
    }

    /** Lays out the book once both the book and the screen size are known. */
    private fun start() {
        val book = opened ?: return
        val viewport = viewport ?: return
        if (state != null) return
        val first = ReaderState.open(book.book.pages.size, book.start, book.config, viewport)
        state = first
        changed(null, first)
    }

    private fun changed(before: ReaderState?, after: ReaderState) {
        val book = opened ?: return
        if (before == null || before.pagination !== after.pagination || before.shownViews != after.shownViews ||
            before.viewport != after.viewport || before.config != after.config
        ) {
            book.prefetcher.update(after.prefetchInput(paused = stripShown))
        }
        book.progress.next(after, Instant.now())?.let(book.services.progress::submit)
    }

    private suspend fun openBook(services: ReaderServices, copyDir: File): Pair<BookId, BookResult> = when (ref) {
        is BookRef.Folder -> {
            val id = folderBookId(UriString(ref.uri.toString()))
            id to Books.openTreeFolder(application.contentResolver, ref.uri, null, copyDir, services.decoding)
        }
        is BookRef.Document -> {
            val source = try {
                application.contentResolver.openByteSource(ref.uri, copyDir)
            } catch (e: Exception) {
                return BookId("") to BookResult.Failed(e.openFailure())
            }
            val id = try {
                documentBookId(prefixOf(source), source.size)
            } catch (e: Exception) {
                source.close()
                return BookId("") to BookResult.Failed(e.openFailure())
            }
            id to Books.openBook(source, services.decoding)
        }
    }

    private fun prefixOf(source: ByteSource): ByteArray {
        val prefix = ByteBuffer.allocate(minOf(source.size, BOOK_ID_PREFIX_BYTES.toLong()).toInt())
        while (prefix.hasRemaining() && source.read(prefix.position().toLong(), prefix) > 0) Unit
        return prefix.array().copyOf(prefix.position())
    }
}

private const val THUMBNAILS = 64

private fun Exception.openFailure(): BookFailure = when (this) {
    is FileNotFoundException, is IllegalArgumentException -> BookFailure.NotFound
    is SecurityException -> BookFailure.NoPermission
    is NoRoomForCopyException -> BookFailure.NoRoomForCopy
    else -> BookFailure.ReadError
}
