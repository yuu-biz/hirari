package io.github.yuubiz.hirari.feature.reader

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.data.ProgressWriter
import io.github.yuubiz.hirari.core.data.Storage
import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.Books
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.MarginScan
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.PreparedImage
import io.github.yuubiz.hirari.core.image.RawPage
import io.github.yuubiz.hirari.core.model.BOOK_ID_PREFIX_BYTES
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.ImageType
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.documentBookId
import io.github.yuubiz.hirari.core.model.folderBookId
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.model.normalizePath
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.NoRoomForCopyException
import io.github.yuubiz.hirari.core.source.documentInfo
import io.github.yuubiz.hirari.core.source.findDocumentId
import io.github.yuubiz.hirari.core.source.openByteSource
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import io.github.yuubiz.hirari.feature.reader.prefetch.Prefetcher
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchDepths
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchInput
import io.github.yuubiz.hirari.feature.reader.prefetch.shownViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** What the app shares with every reader: one of each per process. */
class ReaderServices(
    val storage: Storage,
    val progress: ProgressWriter,
    val decoding: ImageDecoding,
    /** Outlives every screen, on `Dispatchers.IO`. */
    val appScope: CoroutineScope,
    val siblings: SiblingBooks = SiblingBooks.None,
    val smb: SmbServers = SmbServers.None,
) {
    val overrides = BookOverridesStore(storage.books, appScope)
}

/**
 * The book's path for the page bar (docs/spec.md section 4.8, ページバー). A picked book shows the last segment of
 * its URI, which a device's storage gives as `primary:Manga/Book.zip`; a book in a place shows the place's name
 * and the path inside it once the place is read.
 */
private fun BookAddress.initialPath(): String = when (this) {
    is BookAddress.Document -> Uri.parse(uri.value).let { it.lastPathSegment ?: uri.value }
    is BookAddress.Tree -> Uri.parse(uri.value).let { it.lastPathSegment ?: uri.value }
    is BookAddress.InLocation -> normalizePath(path)
}

/**
 * Where an opened book was found: its id, the source the index records, and the document whose name and dates
 * describe it (for a folder, the folder's own document).
 */
private class Located(
    val id: BookId,
    val source: BookSource,
    val documentUri: Uri,
    val folder: Boolean,
    val result: BookResult,
    val remote: Remote? = null,
)

/** A book read from an SMB server, the size of its file, and the meter of what it fetched. */
private class Remote(val server: SmbServer, val size: Long, val meter: FetchMeter)

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
    private val request: OpenBookRequest,
) : ViewModel() {
    var phase: ReaderPhase by mutableStateOf(ReaderPhase.Opening)
        private set

    var state: ReaderState? by mutableStateOf(null)
        private set

    /** `画面の向き`: the setting until the book opens, then the reader's, which outlives the activity turning. */
    var orientation: ReaderOrientation by mutableStateOf(ReaderOrientation.System)
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
        val prefetcher: Prefetcher<RawPage, Bitmap>,
        val services: ReaderServices,
        val progress: ProgressLog,
        val overrides: BookOverridesStore.OverridesWriter,
        /** The reader as the global settings alone set it up, which a change back to stores as no override. */
        val global: ReaderConfig,
        val shareDir: File,
        val loader: BookPageLoader,
        val remote: Remote?,
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

    var bookPath: String by mutableStateOf(request.book.initialPath())
        private set

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

    /**
     * ページを共有: the data of [page] as the book holds it, or a PDF page drawn as PNG, written for other apps
     * to read (docs/spec.md section 4.8). `null` when the page cannot be read or written.
     */
    suspend fun sharePage(page: Int): SharedPage? {
        val book = opened ?: return null
        val entry = book.book.pages.getOrNull(page) ?: return null
        val raw = (book.book.raw(page) as? PageResult.Ready)?.image ?: return null
        val (type, write) = when (raw) {
            is RawPage.Encoded -> raw.type to raw::writeTo
            RawPage.Drawn -> ImageType.PdfPage to (drawn(book.book, page) ?: return null)
        }
        val file = SharedPageFiles.write(book.shareDir, shareFileName(entry.name, type, page), write) ?: return null
        val uri = withContext(Dispatchers.IO) { FileProvider.getUriForFile(application, "${application.packageName}$SHARE_AUTHORITY", file) }
        return SharedPage(uri, shareMimeType(type))
    }

    /** A PDF page drawn at the size of its shape, to be written as PNG. */
    private suspend fun drawn(book: OpenBook, page: Int): ((OutputStream) -> Unit)? {
        val shape = (book.shape(page) as? PageResult.Ready)?.image ?: return null
        val image = (book.page(page, PixelSize(shape.width, shape.height)) as? PageResult.Ready)?.image as? PreparedImage.Whole ?: return null
        return { out ->
            try {
                image.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } finally {
                image.bitmap.recycle()
            }
        }
    }

    /** What running into an edge of the book asks of the screen, until it says it is handled. */
    var bookEnd: BookEndEffect? by mutableStateOf(null)
        private set

    fun bookEndHandled() {
        bookEnd = null
    }

    private val siblings = HashMap<BookEdge, Sibling>()

    /** The book beside this one in its folder toward [edge], asked of the library once per edge. */
    suspend fun sibling(edge: BookEdge): Sibling {
        siblings[edge]?.let { return it }
        val found = services.await().siblings.beside(request.book, edge)
        return found.also { siblings[edge] = it }
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

    /** 再試行 on a page's card. When every reconnect attempt failed, it also starts them over (docs/spec.md section 6.5). */
    fun retry(page: Int) {
        opened?.prefetcher?.retry(page)
        opened?.remote?.server?.retry()
    }

    /** What the book has fetched from its server, for 取得の状況; `null` for a book not on a server. */
    val fetchedBytes: Long? get() = opened?.remote?.meter?.bytes

    /** What the pages of [view] still need from the server: the sizes declared for those whose data is not held. */
    fun fetchExpected(view: PageView): Long {
        val book = opened ?: return 0
        return view.pages.filter { book.prefetcher.raw(it) == null }.sumOf { book.loader.rawEstimate(it) }
    }

    /**
     * Runs a command of the command bar. What only the screen can do comes back: a sheet to show, or
     * [CommandEffect.NotYet] for a command of a later milestone.
     */
    fun command(command: ReaderCommand, nowMs: Long): CommandEffect? {
        val current = state ?: return null
        when (val effect = current.config.effectOf(command)) {
            is CommandEffect.Configure -> dispatch(ReaderEvent.ConfigChanged(effect.config))
            CommandEffect.ShiftForward -> act(ReaderAction.Shift(forward = true), nowMs)
            CommandEffect.StartAutoPlay -> dispatch(ReaderEvent.AutoPlayStarted(nowMs))
            CommandEffect.SharePage -> return shareChoice(current.current)
            is CommandEffect.Show, is CommandEffect.Share, CommandEffect.NotYet -> return effect
        }
        return null
    }

    /** The global `adjust*` settings, where 全体の設定に戻す of the 画像補正 sheet goes back to. */
    val globalAdjust: ImageAdjust get() = opened?.global?.adjust ?: ImageAdjust()

    /** The reader left the screen. The process may end before [onCleared], so the history is written now. */
    fun onStop() {
        dispatch(ReaderEvent.AutoPlayStopped)
        stops.stop()
    }

    override fun onCleared() {
        application.unregisterComponentCallbacks(trimCallbacks)
        thumbnails.clear()
        opened?.let {
            it.prefetcher.close()
            it.services.progress.flush()
            it.services.appScope.launch { SharedPageFiles.clear(it.shareDir) }
        }
        stops.stop()
        bookOwner.release()
    }

    private suspend fun open() {
        val services = services.await()
        val (copyDir, shareDir) = withContext(Dispatchers.IO) { File(application.cacheDir, "copy") to File(application.cacheDir, SHARE_DIR) }
        // What a reader of an earlier process shared is left only by a process that ended before closing it.
        if (firstReader.getAndSet(false)) services.appScope.launch { SharedPageFiles.clear(shareDir) }
        val settings = services.storage.settings.settings.first()
        orientation = settings.readerOrientation
        val located = withContext(Dispatchers.IO) {
            openBook(services, copyDir).also { (it.result as? BookResult.Opened)?.let { opened -> bookOwner.take(opened.book) } }
        }
        val id = located.id
        val book = when (val result = located.result) {
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
        val writer = services.overrides.open(id)
        val config = ReaderConfig.of(settings, writer.current)
        val indexed = withContext(Dispatchers.IO) { describe(located, book) }
        val start = when (val at = request.start) {
            StartAt.Saved -> (saved?.position as? ReadingPosition.Image)?.viewStart
            is StartAt.Page -> at.index
            is StartAt.Image -> book.pages.indexOfFirst { it.name.substringAfterLast('/') == at.name }.takeIf { it >= 0 }
        }?.coerceIn(book.pages.indices) ?: 0
        val lowRam = application.getSystemService(ActivityManager::class.java).isLowRamDevice
        val listener = object : Prefetcher.Listener {
            override fun onShape(page: Int, shape: PageShape.Known) = dispatch(ReaderEvent.ShapeLearned(page, shape))

            override fun onChanged() {
                prepared++
            }

            override fun onMargins(page: Int, scan: MarginScan) = dispatch(ReaderEvent.MarginsLearned(page, scan))
        }
        val loader = BookPageLoader(book, services.decoding.dispatcher)
        val prefetcher = Prefetcher(viewModelScope, loader, ::recycleAfterFrames, services.decoding.budgetBytes, prefetchDepths(lowRam), listener)
        val session = ReadingSession(indexed, openedAt, settings.historyLimit, services.storage.books, services.storage.history, services.appScope)
        val remote = located.remote
        opened = Opened(book, id, start, config, prefetcher, services, ProgressLog(id, book.pages.size, saved), writer, ReaderConfig.of(settings), shareDir, loader, remote)
        remote?.server?.state?.let { connection -> viewModelScope.launch { connection.collect { dispatch(ReaderEvent.ConnectionChanged(it)) } } }
        stops.attach(session)
        phase = ReaderPhase.Reading
        start()
    }

    /**
     * The index entry of the opened [book] (docs/spec.md section 2.2). What the provider does not report stays
     * unknown: no name falls back to the path, no size to 0, and no modification time to the epoch.
     */
    private suspend fun describe(located: Located, book: OpenBook): Book {
        val info = try {
            application.contentResolver.documentInfo(located.documentUri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val size = located.remote?.size ?: if (located.folder) book.pages.sumOf { it.declaredSize ?: 0L } else info?.size ?: 0L
        val fallbackName = when (val source = located.source) {
            is BookSource.InLocation -> source.path.substringAfterLast('/')
            is BookSource.External -> request.book.initialPath()
        }
        return Book(
            id = located.id,
            source = located.source,
            format = BookFormat.Images(book.container),
            title = info?.name ?: fallbackName,
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
        val first = ReaderState.open(book.book.pages.size, book.start, book.config, viewport).copy(connection = book.remote?.server?.state?.value)
        state = first
        changed(null, first)
    }

    private fun changed(before: ReaderState?, after: ReaderState) {
        val book = opened ?: return
        if (before == null || before.pagination !== after.pagination || before.shownViews != after.shownViews ||
            before.viewport != after.viewport || before.config != after.config || before.margins !== after.margins
        ) {
            book.prefetcher.update(after.prefetchInput(paused = stripShown))
        }
        book.progress.next(after, Instant.now())?.let(book.services.progress::submit)
        orientation = after.config.orientation
        if (before != null && before.config !== after.config) {
            book.overrides.update { it.changedBy(before.config, after.config, book.global) }
        }
        readForLoupe(book.loader, after.loupeSource())
        if (reconnected(before?.connection, after.connection)) book.prefetcher.retryLost()
        val hit = after.edgeHit
        if (hit != null && hit != before?.edgeHit) {
            viewModelScope.launch { bookEnd = bookEndEffect(hit.edge, after.config.atBookEnd, ::sibling) }
        }
    }

    /**
     * The page under the loupe at the size of its own image, for the magnifications of the source image. Only the
     * page under the finger is read, and only while the loupe is up; until it arrives the loupe stretches the
     * prepared view.
     */
    var loupeDetail: LoupeDetail? by mutableStateOf(null)
        private set

    private var loupeWanted: LoupeSource? = null
    private var loupeRead: Job? = null

    private fun readForLoupe(loader: BookPageLoader, wanted: LoupeSource?) {
        if (wanted == loupeWanted) return
        loupeWanted = wanted
        loupeRead?.cancel()
        loupeRead = null
        loupeDetail?.takeIf { it.source != wanted }?.let {
            loupeDetail = null
            recycleAfterFrames(it.bitmap)
        }
        if (wanted == null || loupeDetail != null) return
        loupeRead = viewModelScope.launch {
            val result = loader.prepare(wanted.page, wanted.size, wanted.steps, opened?.prefetcher?.raw(wanted.page))
            val bitmap = (result as? PageResult.Ready)?.image?.image ?: return@launch
            if (loupeWanted == wanted) loupeDetail = LoupeDetail(wanted, bitmap) else bitmap.recycle()
        }
    }

    private suspend fun openBook(services: ReaderServices, copyDir: File): Located = when (val book = request.book) {
        is BookAddress.Tree -> {
            val tree = Uri.parse(book.uri.value)
            openFolder(folderBookId(book.uri), BookSource.External(book.uri), tree, DocumentsContract.getTreeDocumentId(tree), services, copyDir)
        }
        is BookAddress.Document -> openDocument(id = null, BookSource.External(book.uri), Uri.parse(book.uri.value), services, copyDir)
        is BookAddress.InLocation -> openInLocation(book, services, copyDir)
    }

    /** A book in a place, by its path inside the place (docs/spec.md section 2.2). */
    private suspend fun openInLocation(book: BookAddress.InLocation, services: ReaderServices, copyDir: File): Located {
        val path = normalizePath(book.path)
        val source = BookSource.InLocation(book.location, path)
        fun failed(reason: BookFailure) = Located(BookId(""), source, Uri.EMPTY, book.folder, BookResult.Failed(reason))
        val location = when (val found = services.storage.locations.get(book.location)) {
            is LibraryLocation.SafTree -> found
            is LibraryLocation.SmbShare -> return openOnServer(found, path, book.folder, source, services)
            null -> return failed(BookFailure.NotFound)
        }
        if (!location.permissionHeld) return failed(BookFailure.NoPermission)
        withContext(Dispatchers.Main) { bookPath = if (path.isEmpty()) location.displayName else "${location.displayName}/$path" }
        val tree = Uri.parse(location.treeUri.value)
        val documentId = try {
            book.documentId ?: application.contentResolver.findDocumentId(tree, path)
        } catch (e: Exception) {
            return failed(e.openFailure())
        } ?: return failed(BookFailure.NotFound)
        val id = locationBookId(location, path)
        return if (book.folder) {
            openFolder(id, source, tree, documentId, services, copyDir)
        } else {
            openDocument(id, source, DocumentsContract.buildDocumentUriUsingTree(tree, documentId), services, copyDir)
        }
    }

    private suspend fun openFolder(id: BookId, source: BookSource, tree: Uri, documentId: String, services: ReaderServices, copyDir: File) = Located(
        id = id,
        source = source,
        documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
        folder = true,
        result = Books.openTreeFolder(application.contentResolver, tree, documentId, copyDir, services.decoding),
    )

    /**
     * A book in an SMB place, read through the server's shared connection (docs/spec.md sections 3.1 and 6.5). A
     * password that cannot be read is a missing permission, and the connection breaking while the book opens is a
     * read failure. The images of a folder on a server are not read yet.
     */
    private suspend fun openOnServer(location: LibraryLocation.SmbShare, path: String, folder: Boolean, source: BookSource, services: ReaderServices): Located {
        fun failed(reason: BookFailure) = Located(BookId(""), source, Uri.EMPTY, folder, BookResult.Failed(reason))
        withContext(Dispatchers.Main) { bookPath = if (path.isEmpty()) location.displayName else "${location.displayName}/$path" }
        if (folder) return failed(BookFailure.ReadError)
        val server = services.smb.serverOf(location) ?: return failed(BookFailure.NoPermission)
        val file = try {
            server.open(normalizePath("${location.basePath}/$path"))
        } catch (e: Exception) {
            return failed(e.openFailure())
        }
        val id = locationBookId(location, path)
        val meter = FetchMeter()
        val result = Books.openBook(meter.counted(file), services.decoding, File(application.cacheDir, "extract/${id.value}"))
        return Located(id, source, Uri.EMPTY, folder = false, result, Remote(server, file.size, meter))
    }

    /** A picked document has no lasting address, so its [id] comes from its first bytes when the caller has none. */
    private suspend fun openDocument(id: BookId?, source: BookSource, uri: Uri, services: ReaderServices, copyDir: File): Located {
        fun failed(e: Exception) = Located(BookId(""), source, uri, folder = false, BookResult.Failed(e.openFailure()))
        val bytes = try {
            application.contentResolver.openByteSource(uri, copyDir)
        } catch (e: Exception) {
            return failed(e)
        }
        val bookId = id ?: try {
            documentBookId(prefixOf(bytes), bytes.size)
        } catch (e: Exception) {
            bytes.close()
            return failed(e)
        }
        return Located(bookId, source, uri, folder = false, Books.openBook(bytes, services.decoding, File(application.cacheDir, "extract/${bookId.value}")))
    }

    private fun prefixOf(source: ByteSource): ByteArray {
        val prefix = ByteBuffer.allocate(minOf(source.size, BOOK_ID_PREFIX_BYTES.toLong()).toInt())
        while (prefix.hasRemaining() && source.read(prefix.position().toLong(), prefix) > 0) Unit
        return prefix.array().copyOf(prefix.position())
    }
}

private const val THUMBNAILS = 64

/** The authority of [SharedPageProvider] after the package name, as `AndroidManifest.xml` declares it. */
private const val SHARE_AUTHORITY = ".share"

/** Whether no reader has opened in this process yet. */
private val firstReader = AtomicBoolean(true)

/** The pixels of [source] for the loupe. */
class LoupeDetail(val source: LoupeSource, val bitmap: Bitmap)

/** A page written for other apps: its content URI and the MIME type to send it as. */
data class SharedPage(val uri: Uri, val mimeType: String)

private fun Exception.openFailure(): BookFailure = when (this) {
    is FileNotFoundException, is IllegalArgumentException -> BookFailure.NotFound
    is SecurityException -> BookFailure.NoPermission
    is NoRoomForCopyException -> BookFailure.NoRoomForCopy
    else -> BookFailure.ReadError
}
