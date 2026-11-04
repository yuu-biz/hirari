package io.github.yuubiz.hirari.feature.reader

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.data.OpeningBook
import io.github.yuubiz.hirari.core.data.ProgressWriter
import io.github.yuubiz.hirari.core.data.Storage
import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.Books
import io.github.yuubiz.hirari.core.image.DiskStash
import io.github.yuubiz.hirari.core.image.EntryNaming
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.MarginScan
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.image.PageOrder
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.PreparedImage
import io.github.yuubiz.hirari.core.image.RawPage
import io.github.yuubiz.hirari.core.image.Tiling
import io.github.yuubiz.hirari.core.model.BOOK_ID_PREFIX_BYTES
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.Bookmark
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookEndEffect
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.EntryOrder
import io.github.yuubiz.hirari.core.model.FallbackCharset
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.ImageType
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.Sibling
import io.github.yuubiz.hirari.core.model.Siblings
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.bookEndEffect
import io.github.yuubiz.hirari.core.model.documentBookId
import io.github.yuubiz.hirari.core.model.folderBookId
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.model.normalizePath
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.NoRoomForCopyException
import io.github.yuubiz.hirari.core.source.documentInfo
import io.github.yuubiz.hirari.core.source.locateDocument
import io.github.yuubiz.hirari.core.source.openByteSource
import io.github.yuubiz.hirari.core.source.smb.SmbPrefetchCache
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import io.github.yuubiz.hirari.core.ui.FirstPageTrace
import io.github.yuubiz.hirari.feature.reader.prefetch.DiskPrefetch
import io.github.yuubiz.hirari.feature.reader.prefetch.Prefetcher
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchDepths
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchInput
import io.github.yuubiz.hirari.feature.reader.prefetch.shownViews
import io.github.yuubiz.hirari.feature.reader.prefetch.travelDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
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
    val siblings: Siblings<BookAddress, BookAddress> = Siblings.None,
    val smb: SmbServers = SmbServers.None,
    /** The SMB prefetch layer on disk, `cacheDir/smb/` (docs/spec.md section 5.3). */
    val smbCache: SmbPrefetchCache? = null,
    val covers: CoverRemaker = CoverRemaker.None,
) {
    val overrides = BookOverridesStore(storage.books, appScope)
}

/**
 * Makes the thumbnails of a book's cover again once the reader chose another page for it (docs/spec.md section 4.8,
 * 表紙にする). The lists own the thumbnails, so the app hands this in.
 */
fun interface CoverRemaker {
    suspend fun remake(book: BookId)

    companion object {
        val None = CoverRemaker {}
    }
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
 * describe it (for a folder, the folder's own document). [overrides] is the book's writer, opened before the book
 * since its chosen charset decodes the names (docs/spec.md section 3.4); null when the book was not found.
 * [fileSize] is the size of a file on a server, which has no document to report it.
 */
private class Located(
    val id: BookId,
    val source: BookSource,
    val documentUri: Uri,
    val folder: Boolean,
    val result: BookResult,
    val remote: Remote? = null,
    val overrides: BookOverridesStore.OverridesWriter? = null,
    val fileSize: Long? = null,
)

/** A book read from an SMB server, and the meter of what it fetched. */
private class Remote(val server: SmbServer, val meter: FetchMeter)

/** The page of an indexed book that 範囲を選んで表紙にする works on. */
data class CoverRangeTarget(val book: BookId, val entry: Int)

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
    microphone: Microphone = DeviceMicrophone,
) : ViewModel() {
    var phase: ReaderPhase by mutableStateOf(ReaderPhase.Opening)
        private set

    /** 息での操作: a settled step goes through the stops as the volume keys do (docs/spec.md section 4.8, 停止点). */
    val breath = BreathSession(viewModelScope, microphone) { if (!breathHeld) act(ReaderAction.StepStops(it), SystemClock.uptimeMillis()) }
    private var breathSetting = BreathInput()
    private var resumed = false

    /** A panel or the guide is up, so what the microphone hears neither turns nor scrolls. */
    var breathHeld = false
    private var breathLeg: BreathLeg? = null

    var state: ReaderState? by mutableStateOf(null)
        private set

    /** `画面の向き`: the setting until the book opens, then the reader's, which outlives the activity turning. */
    var orientation: ReaderOrientation by mutableStateOf(ReaderOrientation.System)
        private set

    /** `背景色` (docs/spec.md section 4.10): the setting from the start, followed live, so the opening and failing screens wear it too. */
    var background: ReaderBackground by mutableStateOf(ReaderBackground.DarkGray)
        private set

    /** `システムバーを隠す`: the setting from the start, so the opening screen follows it too. */
    var hideSystemBars: Boolean by mutableStateOf(true)
        private set

    /** 画面の明るさ in percent, or `null` for the device's, kept apart from [state] so a turn does not touch it. */
    var screenBrightness: Int? by mutableStateOf(null)
        private set

    /** Changes whenever a view is prepared or dropped, or a page fails. */
    var prepared: Int by mutableIntStateOf(0)
        private set

    /** Changes whenever a tile of 縦に連続 arrives or goes. */
    var tilesDrawn: Int by mutableIntStateOf(0)
        private set

    private val stripTiles = StripTiles(
        viewModelScope,
        open = ::openTiles,
        close = { source -> services.getCompleted().appScope.launch { source.close() } },
        changed = { tilesDrawn++ },
    )

    /** The tiles of [page] read so far, which draw over its prepared page in 縦に連続. */
    fun drawnTiles(page: Int): List<DrawnTile> = stripTiles.drawn(page)

    /** The tiles of [page], from its data the raw-data layer holds when it has it. */
    private suspend fun openTiles(page: Int): TileOpening {
        val book = opened ?: return TileOpening.Whole
        val current = state ?: return TileOpening.Whole
        val shape = current.pagination.shapes[page] as? PageShape.Known ?: return TileOpening.Whole
        return when (val result = book.book.page(page, shape.atWidth(current.viewport.width), book.prefetcher.raw(page), Tiling.AsPlanned)) {
            is PageResult.Ready -> when (val image = result.image) {
                is PreparedImage.Tiled -> TileOpening.Tiled(image.source)
                is PreparedImage.Whole -> TileOpening.Whole.also { image.bitmap.recycle() }
            }
            is PageResult.Failed -> if (result.retryable) TileOpening.Failed else TileOpening.Whole
            is PageResult.Unsupported -> TileOpening.Whole
        }
    }

    private var opened: Opened? = null

    /** The record of the book this reader opens (docs/spec.md section 6.1), from the moment it is written. */
    private var openingRecord: OpeningRecord? = null

    /** Whether the screen is started, which a stop before the record exists also tells it. */
    private var onScreen = true

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
    private val thumbnails = ThumbnailCache<Bitmap>(PageStrip.THUMBNAIL_BYTES) { it.width.toLong() * it.height * 4 }

    private class Opened(
        val book: OpenBook,
        val id: BookId,
        val start: Int,
        /** How far down [start] the saved position of 縦に連続 is, as a fraction of the page's height. */
        val startOffset: Float,
        /** The setup the reader starts from, which follows the settings until the screen size is known. */
        var config: ReaderConfig,
        val prefetcher: Prefetcher<RawPage, Bitmap>,
        val services: ReaderServices,
        val progress: ProgressLog,
        val overrides: BookOverridesStore.OverridesWriter,
        /** The reader as the global settings alone set it up, which a change back to stores as no override. */
        var global: ReaderConfig,
        var settings: GlobalSettings,
        val shareDir: File,
        val loader: BookPageLoader,
        val session: ReadingSession,
        val remote: Remote?,
        /** The SMB prefetch layer, for an archive on a server that is not extracted (docs/spec.md section 5.3). */
        val disk: DiskPrefetch?,
    )

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                opened?.prefetcher?.trim()
                stripTiles.clear()
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    internal val traces = ReaderTraces()

    init {
        FirstPageTrace.beginIfIdle()
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
        prefetchToDisk()
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
    var bookEnd: BookEndEffect<BookAddress>? by mutableStateOf(null)
        private set

    fun bookEndHandled() {
        bookEnd = null
    }

    private val siblings = HashMap<BookEdge, Sibling<BookAddress>>()

    /** The book beside this one in its folder toward [edge], asked of the library once per edge. */
    suspend fun sibling(edge: BookEdge): Sibling<BookAddress> {
        siblings[edge]?.let { return it }
        val found = services.await().siblings.beside(request.book, edge)
        return found.also { siblings[edge] = it }
    }

    /**
     * The page [page] as the screen that frames a range of it names it (docs/spec.md section 4.4): the book's id and
     * the page's `entryIndex`, or `null` before the book is open.
     */
    fun coverRangeTarget(page: Int): CoverRangeTarget? {
        val book = opened ?: return null
        val entry = book.book.pages.getOrNull(page)?.entryIndex ?: return null
        return CoverRangeTarget(book.id, entry)
    }

    /** The bookmarks of this book in the order of their positions; none until the book is open. */
    fun bookmarks(): Flow<List<Bookmark>> {
        val book = opened ?: return flowOf(emptyList())
        return book.services.storage.bookmarks.inBook(book.id).catch { emit(emptyList()) }
    }

    /**
     * しおりを追加 at the view starting with [page], named [typed] or by default when it is blank (docs/spec.md
     * section 4.8). The write runs on the process's scope; [done] hears on the main thread whether it was stored.
     */
    fun addBookmark(page: Int, typed: String, done: (Boolean) -> Unit) {
        val book = opened ?: return done(false)
        val position = bookmarkAt(page, book.book.pages.size)
        val write = book.services.appScope.async { quietly { book.services.storage.bookmarks.add(book.id, position, bookmarkLabel(typed), Instant.now()) } }
        viewModelScope.launch { done(write.await()) }
    }

    fun deleteBookmark(mark: Bookmark) {
        val book = opened ?: return
        book.services.appScope.launch { quietly { book.services.storage.bookmarks.delete(listOf(mark.id)) } }
    }

    /** Goes to the page of [mark]. */
    fun goTo(mark: Bookmark) {
        val pageCount = opened?.book?.pages?.size ?: return
        mark.page(pageCount)?.let { dispatch(ReaderEvent.GoTo(it)) }
    }

    /**
     * 表紙にする: [page] becomes the cover of the book in the index, whole (docs/spec.md section 4.8). The write runs on
     * the process's scope, so leaving the screen does not stop it, and once it is stored the thumbnails are made
     * again. Whether it was stored; a book the index does not hold has no cover to choose.
     */
    suspend fun setCover(page: Int): Boolean {
        val book = opened ?: return false
        val entry = book.book.pages.getOrNull(page)?.entryIndex ?: return false
        val shared = book.services
        return shared.appScope.storeCover(
            store = {
                book.session.indexed()
                shared.storage.books.setCover(book.id, entry)
                shared.storage.books.get(book.id) != null
            },
            remake = { remakeQuietly(shared.covers, book.id) },
        ).await()
    }

    /** Whether the operation guide was shown to the end before (docs/spec.md section 4.8, 操作ガイド). */
    /** Whether the guide was closed before; a record that cannot be read counts as not seen, so the guide shows. */
    suspend fun guideSeen(): Boolean = try {
        services.await().storage.launch.readerGuideSeen()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    fun markGuideSeen() {
        viewModelScope.launch {
            val shared = services.await()
            shared.appScope.rememberGuideSeen(shared.storage.launch)
        }
    }

    /** 位置を戻して閉じる: the book gets back the position and state it had when it opened. The screen closes next. */
    fun restorePosition() {
        val book = opened ?: return
        book.services.progress.submit(book.progress.restored(Instant.now()))
    }

    fun preparedView(view: PageView) = opened?.prefetcher?.get(view)

    /**
     * The reader shows the pages of its first view, or their failures, or why the book did not open (docs/spec.md
     * sections 4.3 and 6.1). A book that opened has not shown anything yet; its first decode may still fail.
     */
    val showsContent: Boolean
        get() = when (phase) {
            ReaderPhase.Opening -> false
            ReaderPhase.Reading -> prepared.let { state?.let { preparedView(it.current) } != null }
            is ReaderPhase.Failed, ReaderPhase.NoPages -> true
        }

    val failedPages: Map<Int, PageResult<Nothing>> get() = opened?.prefetcher?.failedPages.orEmpty()

    /** Every page failed, so the book shows one error for all of it (docs/spec.md section 4.8, ページのエラー表示). */
    val allPagesFailed: Boolean
        get() = prepared.let { opened?.let { it.prefetcher.failedPages.size == it.book.pages.size } == true }

    fun onViewport(next: Viewport) {
        viewport = next
        val current = state
        if (current != null) dispatch(ReaderEvent.ViewportChanged(next)) else start()
    }

    /** A tick of 「めくりで振動」 for the screen to feel, one for each turn that changed the view. */
    val turnHaptics = Channel<Unit>(Channel.CONFLATED)

    fun dispatch(event: ReaderEvent) {
        val before = state ?: return
        val after = before.reduce(event)
        if (after == before) return
        state = after
        traces.moved(event, before, after) { preparedView(after.current) != null }
        if (turnTicks(event, before, after)) turnHaptics.trySend(Unit)
        changed(before, after)
    }

    fun act(action: ReaderAction, nowMs: Long) = dispatch(ReaderEvent.Act(action, nowMs))

    /** 再試行 on a page's card. When every reconnect attempt failed, it also starts them over (docs/spec.md section 6.5). */
    fun retry(page: Int) {
        opened?.prefetcher?.retry(page)
        opened?.remote?.server?.retry()
        stripTiles.retry()
    }

    /** What the book has fetched from its server, for 取得の状況; `null` for a book not on a server. */
    val fetchedBytes: Long? get() = opened?.remote?.meter?.bytes

    /**
     * What the pages of [view] still need: those whose data is neither in memory nor in the SMB prefetch layer come
     * from the server, at the sizes the book declares.
     */
    suspend fun fetchNeed(view: PageView): FetchNeed {
        val book = opened ?: return FetchNeed(0, fromDisk = false)
        val missing = view.pages.filter { book.prefetcher.raw(it) == null }
        val onDisk = missing.filter { book.book.stash?.holds(it) == true }
        return FetchNeed((missing - onDisk.toSet()).sumOf { book.loader.rawEstimate(it) }, fromDisk = onDisk.isNotEmpty())
    }

    /**
     * Runs a command of the command bar. What only the screen can do comes back, such as a sheet to show.
     */
    fun command(command: ReaderCommand, nowMs: Long): CommandEffect? {
        val current = state ?: return null
        when (val effect = current.config.effectOf(command)) {
            is CommandEffect.Configure -> dispatch(ReaderEvent.ConfigChanged(effect.config))
            CommandEffect.ShiftForward -> act(ReaderAction.Shift(forward = true), nowMs)
            CommandEffect.StartAutoPlay -> dispatch(ReaderEvent.AutoPlayStarted(nowMs))
            CommandEffect.SharePage -> return shareChoice(current.current)
            CommandEffect.SetCover -> return coverChoice(current.current)
            CommandEffect.ReverseOrder -> return reorder { it.reversedTo(it.reverseOrder != true) }
            CommandEffect.AddBookmark -> return CommandEffect.Show(ReaderSheet.AddBookmark(current.current.pages.first()))
            is CommandEffect.Show, is CommandEffect.Share, is CommandEffect.Cover, is CommandEffect.OpenSettings, is CommandEffect.Reopen, CommandEffect.ToggleBreath -> return effect
        }
        return null
    }

    /** The global `adjust*` settings, where 全体の設定に戻す of the 画像補正 sheet goes back to. */
    val globalAdjust: ImageAdjust get() = opened?.global?.adjust ?: ImageAdjust()

    /** The reader came to the foreground or left it (`onResume` and `onPause`). The microphone runs only between them. */
    fun onForeground(foreground: Boolean) {
        resumed = foreground
        refreshBreath()
    }

    /**
     * Gives [BreathSession] what decides the microphone. The permission is read every time, since the user may give
     * or take it in the system settings while the reader is away.
     */
    private fun refreshBreath() {
        val granted = application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        breath.update(breathSetting.copy(granted = granted, foreground = resumed && phase == ReaderPhase.Reading))
    }

    /** 息で操作 and the setting 息でめくる are one value; `false` when it could not be saved. */
    suspend fun saveBreath(on: Boolean): Boolean = try {
        services.await().storage.settings.update { it.copy(breathControl = on) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** A long sound scrolled the screen for [elapsedMs] toward [turn]. */
    fun breathScrolled(turn: Turn, elapsedMs: Float) {
        if (breathHeld) return
        val current = state ?: return
        val scroll = current.breathPan(turn, elapsedMs * BREATH_SCROLL_DP_PER_MS * current.viewport.density, breathLeg)
        breathLeg = scroll?.leg
        scroll?.let { dispatch(it.pan) }
    }

    private fun followBreathSetting(services: ReaderServices) {
        viewModelScope.launch {
            services.storage.settings.settings
                .map { BreathInput(enabled = it.breathControl, sensitivity = it.breathSensitivity) }
                .distinctUntilChanged()
                .catch { }
                .collect {
                    breathSetting = it
                    refreshBreath()
                }
        }
    }

    /**
     * 既定にする (docs/spec.md section 2.9): the book's values become the global settings, and once they are stored,
     * the book's overrides of those values go, so the book follows the global settings again. A value the reader
     * changed meanwhile keeps its override. Both steps run on the process's scope, so leaving the screen does not
     * split them. [done] hears whether it was stored.
     */
    fun makeDefault(done: (Boolean) -> Unit) {
        val book = opened ?: return done(false)
        val config = state?.config ?: return done(false)
        val write = book.services.appScope.async {
            quietly { book.services.storage.settings.update { it.adopting(config) } }.also { stored ->
                if (stored) book.overrides.update { it.adopted(config) }
            }
        }
        viewModelScope.launch { done(write.await()) }
    }

    /** The overrides the book keeps, which 全体の設定に戻す clears. Empty before the book is open. */
    val bookOverrides: BookOverrides get() = opened?.overrides?.current ?: BookOverrides()

    /**
     * 全体の設定に戻す (docs/spec.md section 4.8): the 本ごとの設定 values become the global settings' and every
     * override of them goes, also one that equals the global value.
     */
    fun backToGlobal() {
        val book = opened ?: return
        state?.let { dispatch(ReaderEvent.ConfigChanged(it.config.withBookValues(book.global))) }
        book.overrides.update { it.backToGlobal() }
    }

    /** 画面の明るさ of the 本ごとの設定 sheet, which is the global `readerBrightness`; the reader follows it once stored. */
    fun setBrightness(percent: Int?) {
        val book = opened ?: return
        book.services.appScope.launch { quietly { book.services.storage.settings.update { it.copy(readerBrightness = percent) } } }
    }

    /** The reader left the screen. The process may end before [onCleared], so the history is written now. */
    fun onStop() {
        dispatch(ReaderEvent.AutoPlayStopped)
        stops.stop()
        onScreen = false
        openingRecord?.stopped()
    }

    fun onStart() {
        onScreen = true
        openingRecord?.started()
    }

    override fun onCleared() {
        openingRecord?.left()
        traces.close()
        application.unregisterComponentCallbacks(trimCallbacks)
        thumbnails.clear()
        stripTiles.clear()
        opened?.let {
            it.disk?.stop()
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
        background = settings.readerBackground
        hideSystemBars = settings.hideSystemBars
        val name = request.book.initialPath().substringAfterLast('/')
        OpeningRecord(services.appScope, services.storage.launch, OpeningBook(request.book, name), onScreen).also { openingRecord = it }.begin()
        val located = withContext(Dispatchers.IO) {
            openBook(Opening(services, copyDir, settings.fallbackCharset)).inOrder(settings.entryOrder).also { (it.result as? BookResult.Opened)?.let { opened -> bookOwner.take(opened.book) } }
        }
        val id = located.id
        val book = when (val result = located.result) {
            is BookResult.Failed -> {
                phase = ReaderPhase.Failed(result.reason)
                openingRecord?.shown()
                return
            }
            is BookResult.Opened -> result.book
        }
        if (book.pages.isEmpty()) {
            bookOwner.release()
            phase = ReaderPhase.NoPages
            openingRecord?.shown()
            return
        }
        // A reader closed moments ago may still hold this book's latest position in the writer's window.
        val openedAt = Instant.now()
        services.progress.flush().join()
        val saved = services.storage.progress.get(id)
        val writer = checkNotNull(located.overrides)
        val config = ReaderConfig.of(settings, writer.current)
        val indexed = withContext(Dispatchers.IO) { describe(located, book) }
        val start = startPage(request.start, book.pages, saved?.position)
        val startOffset = startOffset(request.start, start, book.pages, saved?.position)
        val lowRam = application.getSystemService(ActivityManager::class.java).isLowRamDevice
        val listener = object : Prefetcher.Listener {
            override fun onShape(page: Int, shape: PageShape.Known) = dispatch(ReaderEvent.ShapeLearned(page, shape))

            override fun onChanged() {
                prepared++
                if (showsContent) openingRecord?.shown()
                prefetchToDisk()
            }

            override fun onMargins(page: Int, scan: MarginScan) = dispatch(ReaderEvent.MarginsLearned(page, scan))
        }
        val loader = BookPageLoader(book, services.decoding.dispatcher)
        val prefetcher = Prefetcher(viewModelScope, loader, ::recycleAfterFrames, services.decoding.budgetBytes, prefetchDepths(lowRam), listener)
        val session = ReadingSession(indexed, openedAt, settings.historyLimit, services.storage.books, services.storage.history, services.appScope)
        val remote = located.remote
        val disk = book.stash?.let { DiskPrefetch(viewModelScope, it, book.pages.size) }
        opened = Opened(book, id, start, startOffset, config, prefetcher, services, ProgressLog(id, book.pages.size, saved), writer, ReaderConfig.of(settings), settings, shareDir, loader, session, remote, disk)
        remote?.server?.state?.let { connection -> viewModelScope.launch { connection.collect { dispatch(ReaderEvent.ConnectionChanged(it)) } } }
        stops.attach(session)
        phase = ReaderPhase.Reading
        followBreathSetting(services)
        start()
        viewModelScope.launch { services.storage.settings.settings.catch {}.collect(::followSettings) }
    }

    /**
     * The book opened again in the order 書庫内の並び now gives, at the page on screen, once that setting changed while
     * it was open (docs/spec.md section 3.5).
     */
    var reordered: OpenBookRequest? by mutableStateOf(null)
        private set

    /** The global settings changed while the book is open, as on the settings screen opened from the reader. */
    private fun followSettings(settings: GlobalSettings) {
        val book = opened ?: return
        if (settings == book.settings) return
        if (reorders(book.settings, settings, book.book.container)) reordered = againHere()
        book.settings = settings
        background = settings.readerBackground
        book.global = ReaderConfig.of(settings)
        val current = state ?: return run { book.config = book.config.following(settings, book.overrides.current) }
        dispatch(ReaderEvent.ConfigChanged(current.config.following(settings, book.overrides.current)))
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
        val size = if (located.folder) book.pages.sumOf { it.declaredSize ?: 0L } else located.fileSize ?: info?.size ?: 0L
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
        val first = ReaderState.open(book.book.pages.size, book.start, book.config, viewport, book.startOffset).copy(connection = book.remote?.server?.state?.value)
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
        hideSystemBars = after.config.hideSystemBars
        screenBrightness = after.config.brightness
        if (before != null && before.config !== after.config) {
            book.overrides.update { it.changedBy(before.config, after.config, book.global) }
        }
        readForLoupe(book.loader, after.loupeSource())
        stripTiles.update(after.tileWindows())
        prefetchToDisk()
        if (reconnected(before?.connection, after.connection)) {
            book.prefetcher.retryLost()
            book.disk?.restart()
            stripTiles.retry()
        }
        val hit = after.edgeHit
        if (hit != null && hit != before?.edgeHit) {
            viewModelScope.launch { bookEnd = bookEndEffect(hit.edge, after.config.atBookEnd, ::sibling) }
        }
    }

    /**
     * Moves the SMB prefetch layer to the current view once that view is prepared, so the reads it waits on go
     * before those of the layer, and stops it until then. It stops while the thumbnail strip is up, as the other
     * layers do (docs/spec.md section 5.3).
     */
    private fun prefetchToDisk() {
        val book = opened ?: return
        val disk = book.disk ?: return
        val current = state ?: return
        if (stripShown) return disk.stop()
        val view = current.current
        val shown = (view.pages.min()..view.pages.max()).takeIf { book.prefetcher.get(view) != null }
        disk.update(shown, travelDirection(current.recentTurns))
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

    /**
     * The book in the order 書庫内の並び ([entries]) and its overrides ask for: reversed when `reverseOrder` is on
     * (docs/spec.md sections 2.9 and 3.5).
     */
    private fun Located.inOrder(entries: EntryOrder): Located {
        val opened = result as? BookResult.Opened ?: return this
        val order = PageOrder(entries, overrides?.current?.reverseOrder == true)
        if (order == PageOrder()) return this
        return Located(id, source, documentUri, folder, BookResult.Opened(opened.book.ordered(order)), remote, overrides, fileSize)
    }

    /** Whether the book's names come from an archive's bytes, so a charset chosen for them changes them (docs/spec.md section 3.4). */
    val namesHaveCharset: Boolean get() = opened?.book?.container?.let { it in ARCHIVES } == true

    /** Whether the book's pages go in the reverse of their natural order (並びを逆に). */
    val reversed: Boolean get() = opened?.overrides?.current?.reverseOrder == true

    /** The charset chosen for the names of this book, or `null` for the detected one. */
    val nameCharset: Charset? get() = opened?.overrides?.current?.nameCharset

    /**
     * Stores [change] of the book's page order or names, 並びを逆に and 文字コード (docs/spec.md section 4.8), and opens the
     * book again at the page on screen, found by its `entryIndex` in the new order. `null` when nothing changes.
     */
    fun reorder(change: (BookOverrides) -> BookOverrides): CommandEffect? {
        val book = opened ?: return null
        val current = state ?: return null
        val before = book.overrides.current
        book.overrides.update(change)
        if (book.overrides.current == before) return null
        return CommandEffect.Reopen(againHere(book, current))
    }

    /** This book from the page on screen, found by its `entryIndex` once it opens in another order. */
    private fun againHere(book: Opened = checkNotNull(opened), current: ReaderState? = state): OpenBookRequest {
        val anchor = current?.scroll?.anchor
        val page = anchor?.page ?: current?.current?.pages?.first() ?: book.start
        return OpenBookRequest(request.book, StartAt.Entry(book.book.pages[page].entryIndex, anchor?.fraction ?: 0f))
    }

    /** What every way of opening the book shares: the services, where copies go, and the setting `fallbackCharset`. */
    private class Opening(val services: ReaderServices, val copyDir: File, val fallback: FallbackCharset) {
        /** The writer of book [id], and how the names are decoded with the charset it holds (docs/spec.md section 3.4). */
        suspend fun overrides(id: BookId): Pair<BookOverridesStore.OverridesWriter, EntryNaming> =
            services.overrides.open(id).let { it to EntryNaming(it.current.nameCharset, fallback) }
    }

    private suspend fun openBook(opening: Opening): Located = when (val book = request.book) {
        is BookAddress.Tree -> {
            val tree = Uri.parse(book.uri.value)
            openFolder(folderBookId(book.uri), BookSource.External(book.uri), tree, DocumentsContract.getTreeDocumentId(tree), opening)
        }
        is BookAddress.Document -> openDocument(id = null, BookSource.External(book.uri), Uri.parse(book.uri.value), opening)
        is BookAddress.InLocation -> openInLocation(book, opening)
    }

    /** A book in a place, by its path inside the place (docs/spec.md section 2.2). */
    private suspend fun openInLocation(book: BookAddress.InLocation, opening: Opening): Located {
        val path = normalizePath(book.path)
        val source = BookSource.InLocation(book.location, path)
        fun failed(reason: BookFailure) = Located(BookId(""), source, Uri.EMPTY, book.folder, BookResult.Failed(reason))
        val location = when (val found = opening.services.storage.locations.get(book.location)) {
            is LibraryLocation.SafTree -> found
            is LibraryLocation.SmbShare -> return openOnServer(found, path, book.folder, source, opening)
            null -> return failed(BookFailure.NotFound)
        }
        if (!location.permissionHeld) return failed(BookFailure.NoPermission)
        withContext(Dispatchers.Main) { bookPath = if (path.isEmpty()) location.displayName else "${location.displayName}/$path" }
        val tree = Uri.parse(location.treeUri.value)
        val documentId = try {
            book.documentId ?: application.contentResolver.locateDocument(tree, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed(e.openFailure())
        } ?: return failed(BookFailure.NotFound)
        val id = locationBookId(location, path)
        return if (book.folder) {
            openFolder(id, source, tree, documentId, opening)
        } else {
            openDocument(id, source, DocumentsContract.buildDocumentUriUsingTree(tree, documentId), opening)
        }
    }

    private suspend fun openFolder(id: BookId, source: BookSource, tree: Uri, documentId: String, opening: Opening) = Located(
        id = id,
        source = source,
        documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
        folder = true,
        result = Books.openTreeFolder(application.contentResolver, tree, documentId, opening.copyDir, opening.services.decoding),
        overrides = opening.overrides(id).first,
    )

    /**
     * A book in an SMB place, an archive, a PDF, or a folder of images, read through the server's shared connection
     * (docs/spec.md sections 3.1 and 6.5). A password that cannot be read is a missing permission, and the connection
     * breaking while the book opens is a read failure.
     */
    private suspend fun openOnServer(location: LibraryLocation.SmbShare, path: String, folder: Boolean, source: BookSource, opening: Opening): Located {
        fun failed(reason: BookFailure) = Located(BookId(""), source, Uri.EMPTY, folder, BookResult.Failed(reason))
        withContext(Dispatchers.Main) { bookPath = if (path.isEmpty()) location.displayName else "${location.displayName}/$path" }
        val server = opening.services.smb.serverOf(location) ?: return failed(BookFailure.NoPermission)
        val id = locationBookId(location, path)
        val (overrides, naming) = opening.overrides(id)
        val meter = FetchMeter()
        val remote = Remote(server, meter)
        val serverPath = normalizePath("${location.basePath}/$path")
        if (folder) {
            val result = Books.openServerFolder(server, serverPath, opening.services.decoding, meter::counted)
            return Located(id, source, Uri.EMPTY, folder = true, result, remote, overrides)
        }
        val file = try {
            server.open(serverPath)
        } catch (e: Exception) {
            return failed(e.openFailure())
        }
        val stash = opening.services.smbCache?.let { DiskStash(it, id, file.size, file.modified) }
        val result = Books.openBook(meter.counted(file), opening.services.decoding, File(application.cacheDir, "extract/${id.value}"), naming, stash)
        return Located(id, source, Uri.EMPTY, folder = false, result, remote, overrides, fileSize = file.size)
    }

    /** A picked document has no lasting address, so its [id] comes from its first bytes when the caller has none. */
    private suspend fun openDocument(id: BookId?, source: BookSource, uri: Uri, opening: Opening): Located {
        fun failed(e: Exception) = Located(BookId(""), source, uri, folder = false, BookResult.Failed(e.openFailure()))
        val bytes = try {
            application.contentResolver.openByteSource(uri, opening.copyDir)
        } catch (e: Exception) {
            return failed(e)
        }
        val bookId = id ?: try {
            documentBookId(prefixOf(bytes), bytes.size)
        } catch (e: Exception) {
            bytes.close()
            return failed(e)
        }
        val (overrides, naming) = try {
            opening.overrides(bookId)
        } catch (e: Throwable) {
            bytes.close()
            throw e
        }
        val result = Books.openBook(bytes, opening.services.decoding, File(application.cacheDir, "extract/${bookId.value}"), naming)
        return Located(bookId, source, uri, folder = false, result, overrides = overrides)
    }

    private fun prefixOf(source: ByteSource): ByteArray {
        val prefix = ByteBuffer.allocate(minOf(source.size, BOOK_ID_PREFIX_BYTES.toLong()).toInt())
        while (prefix.hasRemaining() && source.read(prefix.position().toLong(), prefix) > 0) Unit
        return prefix.array().copyOf(prefix.position())
    }
}


private val ARCHIVES = setOf(Container.Zip, Container.Rar, Container.SevenZip, Container.Tar)

/** The authority of [SharedPageProvider] after the package name, as `AndroidManifest.xml` declares it. */
private const val SHARE_AUTHORITY = ".share"

/** Whether no reader has opened in this process yet. */
private val firstReader = AtomicBoolean(true)

/**
 * The page the reader opens on for [at], among [pages], with [saved] the stored position. What names no page, as an
 * image or entry the book no longer holds, opens the first page; a page past the end opens the last.
 */
internal fun startPage(at: StartAt, pages: List<PageEntry>, saved: ReadingPosition?): Int = when (at) {
    StartAt.Saved -> (saved as? ReadingPosition.Image)?.viewStart
    is StartAt.Page -> at.index
    is StartAt.Image -> pages.indexOfFirst { it.name.substringAfterLast('/') == at.name }.takeIf { it >= 0 }
    is StartAt.Entry -> pages.indexOfFirst { it.entryIndex == at.entryIndex }.takeIf { it >= 0 }
}?.coerceIn(pages.indices) ?: 0

/**
 * How far down page [start] 縦に連続 opens, as a fraction of its height (docs/spec.md section 2.7): the stored or the
 * carried offset when [start] is still the page it was taken on, else the top of the page.
 */
internal fun startOffset(at: StartAt, start: Int, pages: List<PageEntry>, saved: ReadingPosition?): Float = when (at) {
    StartAt.Saved -> (saved as? ReadingPosition.Image)?.takeIf { it.viewStart == start }?.offset
    is StartAt.Entry -> at.offset.takeIf { pages.getOrNull(start)?.entryIndex == at.entryIndex }
    is StartAt.Page, is StartAt.Image -> null
} ?: 0f

/**
 * Whether the change of the global settings from [before] to [after] puts a book from [container] in another order,
 * which opens it again (docs/spec.md section 3.5). A folder keeps its natural order under 格納順.
 */
internal fun reorders(before: GlobalSettings, after: GlobalSettings, container: Container): Boolean =
    before.entryOrder != after.entryOrder && container != Container.Folder

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

/** Whether [write] went through; a write the database refuses is reported, not thrown. */
private suspend fun quietly(write: suspend () -> Unit): Boolean = try {
    write()
    true
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

/** A thumbnail that fails to be made again is made by the lists when they show the book. */
private suspend fun remakeQuietly(covers: CoverRemaker, book: BookId) {
    try {
        covers.remake(book)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}
