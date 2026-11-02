package io.github.yuubiz.hirari.feature.reader

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
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
import io.github.yuubiz.hirari.core.model.BOOK_ID_PREFIX_BYTES
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.StopOrder
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.documentBookId
import io.github.yuubiz.hirari.core.model.folderBookId
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.NoRoomForCopyException
import io.github.yuubiz.hirari.core.source.openByteSource
import io.github.yuubiz.hirari.feature.reader.prefetch.Prefetcher
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchDepths
import io.github.yuubiz.hirari.feature.reader.prefetch.prefetchInput
import io.github.yuubiz.hirari.feature.reader.prefetch.shownViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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
    private var viewport: Viewport? = null

    private class Opened(
        val book: OpenBook,
        val id: BookId,
        val start: Int,
        val config: ReaderConfig,
        val prefetcher: Prefetcher<Bitmap>,
        val services: ReaderServices,
        var read: Boolean,
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

    fun preparedView(view: PageView) = opened?.prefetcher?.get(view)

    val failedPages: Map<Int, PageResult<Nothing>> get() = opened?.prefetcher?.failedPages.orEmpty()

    /** Every page failed, so the book shows one error for all of it (docs/spec.md section 4.8, ページのエラー表示). */
    val allPagesFailed: Boolean get() = opened?.let { it.prefetcher.failedPages.size == it.book.pages.size } == true

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

    /** Runs a command of the command bar, or returns false for one M1 does not have. */
    fun command(command: ReaderCommand, nowMs: Long): Boolean {
        val current = state ?: return false
        val config = current.config
        val next = when (command) {
            ReaderCommand.Layout -> config.copy(layout = config.layout.copy(mode = config.layout.mode.next()))
            ReaderCommand.ScaleMode -> config.copy(scaleMode = config.scaleMode.next())
            ReaderCommand.Direction -> config.copy(
                layout = config.layout.copy(
                    direction = if (config.direction == ReadingDirection.RightToLeft) ReadingDirection.LeftToRight else ReadingDirection.RightToLeft,
                ),
            )
            ReaderCommand.MirrorControls -> config.copy(tapZones = config.tapZones.copy(mirrored = !config.mirrored))
            ReaderCommand.StopOrder -> config.copy(
                stopOrder = if (config.stopOrder == StopOrder.RowsFirst) StopOrder.ColumnsFirst else StopOrder.RowsFirst,
            )
            ReaderCommand.ShiftPairing -> {
                act(ReaderAction.Shift(forward = true), nowMs)
                return true
            }
            else -> return false
        }
        dispatch(ReaderEvent.ConfigChanged(next))
        return true
    }

    override fun onCleared() {
        application.unregisterComponentCallbacks(trimCallbacks)
        val book = opened ?: return
        book.prefetcher.close()
        book.services.appScope.launch { book.book.close() }
    }

    private suspend fun open() {
        val services = services.await()
        val copyDir = withContext(Dispatchers.IO) { File(application.cacheDir, "copy") }
        val config = ReaderConfig.of(services.storage.settings.settings.first())
        val (id, result) = withContext(Dispatchers.IO) { openBook(services, copyDir) }
        val book = when (result) {
            is BookResult.Failed -> {
                phase = ReaderPhase.Failed(result.reason)
                return
            }
            is BookResult.Opened -> result.book
        }
        if (book.pages.isEmpty()) {
            services.appScope.launch { book.close() }
            phase = ReaderPhase.NoPages
            return
        }
        val saved = try {
            services.storage.progress.get(id)
        } catch (e: CancellationException) {
            services.appScope.launch { book.close() }
            throw e
        }
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
        opened = Opened(book, id, start, config, prefetcher, services, read = saved?.state == ReadState.Read)
        phase = ReaderPhase.Reading
        start()
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
            book.prefetcher.update(after.prefetchInput(paused = false))
        }
        if (before == null || before.current != after.current) save(book, after)
    }

    /** Opening makes the book Reading, and showing its last view makes it Read (docs/spec.md section 2.7). */
    private fun save(book: Opened, state: ReaderState) {
        if (state.isLastView) book.read = true
        val position = ReadingPosition.Image(state.current.pages.first(), book.book.pages.size)
        book.services.progress.submit(BookProgress(book.id, if (book.read) ReadState.Read else ReadState.Reading, position, Instant.now()))
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
            id to Books.openArchive(source, services.decoding)
        }
    }

    private fun prefixOf(source: ByteSource): ByteArray {
        val prefix = ByteBuffer.allocate(minOf(source.size, BOOK_ID_PREFIX_BYTES.toLong()).toInt())
        while (prefix.hasRemaining() && source.read(prefix.position().toLong(), prefix) > 0) Unit
        return prefix.array().copyOf(prefix.position())
    }
}

private fun Exception.openFailure(): BookFailure = when (this) {
    is FileNotFoundException, is IllegalArgumentException -> BookFailure.NotFound
    is SecurityException -> BookFailure.NoPermission
    is NoRoomForCopyException -> BookFailure.NoRoomForCopy
    else -> BookFailure.ReadError
}

private fun LayoutMode.next(): LayoutMode = LayoutMode.entries[(ordinal + 1) % LayoutMode.entries.size]

private fun ScaleMode.next(): ScaleMode = ScaleMode.entries[(ordinal + 1) % ScaleMode.entries.size]
