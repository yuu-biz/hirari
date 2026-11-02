package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.BookmarkRepository
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.data.ProgressRepository
import io.github.yuubiz.hirari.core.data.ProgressWriter
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.Bookmark
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.TextTypeface
import io.github.yuubiz.hirari.core.text.Heading
import io.github.yuubiz.hirari.core.text.LayoutParams
import io.github.yuubiz.hirari.core.text.TextDocument
import io.github.yuubiz.hirari.core.text.TextPage
import io.github.yuubiz.hirari.core.text.VerticalLayout
import io.github.yuubiz.hirari.core.text.decodeText
import io.github.yuubiz.hirari.core.text.detectCharset
import io.github.yuubiz.hirari.core.text.parseAozora
import io.github.yuubiz.hirari.core.text.resolvePicturePath
import io.github.yuubiz.hirari.core.text.tableOfContents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.time.Instant

sealed interface SearchState {
    data object Idle : SearchState

    data class Searching(val query: String) : SearchState

    data class Done(val result: SearchResult) : SearchState
}

sealed interface TextReaderUiState {
    data object Opening : TextReaderUiState

    data class Failed(val reason: TextFailure) : TextReaderUiState

    /**
     * An open text. [run] is null until the first page under the current [frame] is laid out, and [current] indexes
     * its first shown page. [typeface] is the one the pages are laid out and drawn with. [highlight] is the source
     * range of the search hit last shown.
     */
    data class Reading(
        val title: String,
        val settings: GlobalSettings,
        val colors: PageColors,
        val typeface: TextTypeface,
        val frame: PageFrame?,
        val run: PageRun?,
        val current: Int,
        val number: PageNumber?,
        val contents: List<Heading>,
        val bookmarks: List<Bookmark>,
        val search: SearchState,
        val highlight: IntRange?,
    ) : TextReaderUiState {
        /** The shown pages, right to left. */
        val shown: List<TextPage>
            get() = run?.pages?.let { pages ->
                pages.subList(current, minOf(pages.size, current + (frame?.pagesPerView ?: 1)))
            }.orEmpty()
    }
}

/**
 * One open text in the reader (docs/spec.md section 4.9). The reading position is a source offset, the anchor: every
 * open, turn, jump and new layout shows the page that holds it, so a new size or typeface keeps the same sentence on
 * screen. Everything here runs on [scope]'s thread; decoding, parsing, layout and search run on [work], one at a
 * time, since the glyph metrics are not thread-safe.
 */
internal class TextReaderModel(
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher,
    private val opener: TextOpener,
    private val settings: Flow<GlobalSettings>,
    private val progress: ProgressRepository,
    private val writer: ProgressWriter,
    private val bookmarks: BookmarkRepository,
    private val fonts: TextFonts,
    private val history: TextHistory,
    private val clock: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow<TextReaderUiState>(TextReaderUiState.Opening)
    val state: StateFlow<TextReaderUiState> = _state.asStateFlow()

    private var opened: OpenedText? = null
    private var document: TextDocument? = null
    private var title = ""
    private var contents = emptyList<Heading>()
    private var current: GlobalSettings? = null
    private var typeface: TextTypeface = TextTypeface.Mincho
    private var viewport: Viewport? = null
    private var frame: PageFrame? = null
    private var run: PageRun? = null
    private var anchor = 0
    private var highlight: IntRange? = null
    private var search: SearchState = SearchState.Idle
    private var marks = emptyList<Bookmark>()
    private var readState = ReadState.Reading
    private var lastWritten: Pair<ReadState, Int>? = null

    /** A turn asked for before its page was laid out; it runs when the page comes. */
    private var pendingTurn: Int? = null
    private var layoutJob: Job? = null

    /** The params and typeface of the pages in [run], or of the layout making them. */
    private var laidOut: Pair<LayoutParams, TextTypeface>? = null
    private var searchJob: Job? = null
    private var stopped = false
    private var closed = false

    fun start() {
        scope.launch { open() }
    }

    private suspend fun open() {
        val text = when (val result = opener.open()) {
            is TextOpenResult.Failed -> {
                _state.value = TextReaderUiState.Failed(result.reason)
                return
            }
            is TextOpenResult.Opened -> result.text
        }
        if (closed) return text.close()
        opened = text
        val first = settings.first()
        val fallback = Charset.forName(first.fallbackCharset.charsetName)
        val parsed = withContext(work) {
            val decoded = decodeText(text.bytes, detectCharset(text.bytes, fallback).charset)
            parseAozora(decoded).let { it to tableOfContents(it) }
        }
        document = parsed.first
        contents = parsed.second
        title = parsed.first.title ?: text.title
        // A reader closed moments ago may still hold this text's latest position in the writer's window.
        val openedAt = clock()
        writer.flush().join()
        val saved = progress.get(text.id)
        val length = parsed.first.sourceLength
        anchor = ((saved?.position as? ReadingPosition.Text)?.charOffset ?: 0L).coerceIn(0L, length.toLong()).toInt()
        readState = if (saved?.state == ReadState.Read) ReadState.Read else ReadState.Reading
        lastWritten = saved?.let { it.state to ((it.position as? ReadingPosition.Text)?.charOffset?.toInt() ?: -1) }
        history.opened(text.id, text.book, openedAt, first.historyLimit)
        if (stopped) history.stop()
        scope.launch { bookmarks.inBook(text.id).collect { marks = it; render() } }
        scope.launch { settings.collect { apply(it) } }
    }

    fun onViewport(next: Viewport) {
        if (next == viewport) return
        viewport = next
        current?.let { reframe(it, typeface) }
    }

    /** Turns [views] views forward, or back when negative. A turn past either end of the text does nothing. */
    fun turn(views: Int) {
        val run = run ?: return
        val perView = frame?.pagesPerView ?: 1
        val index = run.indexOf(anchor)
        val target = index + views * perView
        when {
            target < 0 && index > 0 -> moveTo(run.pages.first().start)
            target < 0 -> if (!run.fromStart) pendingTurn = views
            target > run.pages.lastIndex -> if (!run.complete) pendingTurn = views
            else -> moveTo(run.pages[target].start)
        }
    }

    fun first() = goTo(0)

    fun last() {
        val document = document ?: return
        val run = run
        if (run != null && run.complete) moveTo(run.pages.last().start) else goTo(maxOf(0, document.sourceLength - 1))
    }

    /** The page bar: [fraction] of the way through the text, by page once every page is laid out and by character before. */
    fun goToFraction(fraction: Float) {
        val document = document ?: return
        val run = run
        val f = fraction.coerceIn(0f, 1f)
        if (run != null && run.fromStart && run.complete) {
            moveTo(run.pages[Math.round(f * run.pages.lastIndex)].start)
        } else {
            goTo((f * document.sourceLength).toInt().coerceAtMost(maxOf(0, document.sourceLength - 1)))
        }
    }

    fun goTo(heading: Heading) = goTo(heading.start)

    fun goTo(bookmark: Bookmark) {
        val offset = (bookmark.position as? ReadingPosition.Text)?.charOffset ?: return
        goTo(offset.toInt())
    }

    fun show(hit: SearchHit) {
        highlight = hit.start until hit.end
        goTo(hit.start, keepHighlight = true)
    }

    fun search(query: String) {
        val document = document ?: return
        searchJob?.cancel()
        search = SearchState.Searching(query)
        render()
        searchJob = scope.launch {
            val result = withContext(work) { searchText(document, query) }
            search = SearchState.Done(result)
            render()
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        search = SearchState.Idle
        highlight = null
        render()
    }

    fun addBookmark() {
        val id = opened?.id ?: return
        val position = position() ?: return
        scope.launch { bookmarks.add(id, position, label = null, createdAt = clock()) }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        scope.launch { bookmarks.delete(listOf(bookmark.id)) }
    }

    /** The bytes of the 挿絵 [path] names, relative to the text, or null when it cannot be read. */
    suspend fun picture(path: String): ByteArray? {
        val text = opened ?: return null
        val reader = text.pictures ?: return null
        val resolved = resolvePicturePath(text.path, path) ?: return null
        return reader.read(resolved)
    }

    /** The reader left the screen: the position goes out now, and the history, since the process may end before [close]. */
    fun onStop() {
        writer.flush()
        stopped = true
        if (opened != null) history.stop()
    }

    fun close() {
        closed = true
        layoutJob?.cancel()
        searchJob?.cancel()
        writer.flush()
        if (opened != null) history.stop()
        opened?.close?.invoke()
        opened = null
    }

    private fun position(): ReadingPosition.Text? {
        val document = document ?: return null
        return ReadingPosition.Text(anchor.toLong(), document.sourceLength.toLong())
    }

    private fun goTo(offset: Int, keepHighlight: Boolean = false) {
        if (!keepHighlight) highlight = null
        val run = run
        if (run != null && run.covers(offset)) {
            moveTo(offset, keepHighlight = true)
        } else {
            anchor = offset
            pendingTurn = null
            relayout()
        }
    }

    private fun moveTo(offset: Int, keepHighlight: Boolean = false) {
        if (!keepHighlight) highlight = null
        anchor = offset
        pendingTurn = null
        render()
        record()
    }

    private suspend fun apply(settings: GlobalSettings) {
        val available = fonts.available(settings.textTypeface)
        current = settings
        reframe(settings, available)
    }

    private fun reframe(settings: GlobalSettings, available: TextTypeface) {
        frame = viewport?.let { pageFrame(it, settings) }
        typeface = available
        if (frame?.params?.let { it to available } != laidOut) relayout() else render()
    }

    private fun relayout() {
        layoutJob?.cancel()
        run = null
        val document = document
        val frame = frame
        laidOut = frame?.params?.let { it to typeface }
        render()
        if (document == null || frame == null) return
        val typeface = typeface
        layoutJob = scope.launch {
            val metrics = fonts.metrics(typeface)
            val setter = withContext(work) { Typesetter(VerticalLayout(document, frame.params, metrics), document.sourceLength, anchor) }
            while (true) {
                val reading = anchor
                val snapshot = withContext(work) { if (setter.step(reading)) setter.snapshot() else null } ?: break
                publish(snapshot)
            }
        }
    }

    private fun publish(snapshot: PageRun) {
        run = snapshot
        val pending = pendingTurn
        pendingTurn = null
        if (pending != null) turn(pending)
        render()
        record()
    }

    /** Hands the position to the writer when it or the read state changed (docs/spec.md section 2.7). */
    private fun record() {
        val run = run ?: return
        val id = opened?.id ?: return
        val position = position() ?: return
        val index = run.indexOf(anchor)
        if (run.complete && index + (frame?.pagesPerView ?: 1) > run.pages.lastIndex) readState = ReadState.Read
        val key = readState to anchor
        if (key == lastWritten) return
        lastWritten = key
        writer.submit(BookProgress(id, readState, position, clock()))
    }

    private fun render() {
        val settings = current ?: return
        if (document == null) return
        val run = run
        val index = run?.indexOf(anchor) ?: 0
        _state.value = TextReaderUiState.Reading(
            title = title,
            settings = settings,
            colors = pageColors(settings),
            typeface = typeface,
            frame = frame,
            run = run,
            current = index,
            number = run?.number(index),
            contents = contents,
            bookmarks = marks,
            search = search,
            highlight = highlight,
        )
    }
}
