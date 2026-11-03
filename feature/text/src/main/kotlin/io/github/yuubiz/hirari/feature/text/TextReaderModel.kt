package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.BookmarkRepository
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.data.ProgressRepository
import io.github.yuubiz.hirari.core.data.ProgressWriter
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.Bookmark
import io.github.yuubiz.hirari.core.model.NeighborStart
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.TextTypeface
import io.github.yuubiz.hirari.core.text.Heading
import io.github.yuubiz.hirari.core.text.LayoutParams
import io.github.yuubiz.hirari.core.text.TextDocument
import io.github.yuubiz.hirari.core.text.TextFootprint
import io.github.yuubiz.hirari.core.text.TextPage
import io.github.yuubiz.hirari.core.text.VerticalLayout
import io.github.yuubiz.hirari.core.text.decodeText
import io.github.yuubiz.hirari.core.text.detectCharset
import io.github.yuubiz.hirari.core.text.parseAozora
import io.github.yuubiz.hirari.core.text.resolvePicturePath
import io.github.yuubiz.hirari.core.text.tableOfContents
import io.github.yuubiz.hirari.core.ui.TurnHaptics
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

/** What [TextReaderModel.turn] did. */
sealed interface TurnResult {
    /** The view moved away from the one whose first page starts at source offset [from]. */
    data class Moved(val from: Int) : TurnResult

    /** The page is not laid out yet; the turn runs once it is. */
    data object Waiting : TurnResult

    /** The turn ran into [edge] of the text. */
    data class Edge(val edge: BookEdge) : TurnResult
}

sealed interface TextReaderUiState {
    data object Opening : TextReaderUiState

    data class Failed(val reason: TextFailure) : TextReaderUiState

    /**
     * An open text. [run] is null until the first page under the current [frame] is laid out, and [current] indexes
     * its first shown page. [pages] holds the content of the pages around it by their start; the others are laid out
     * again when they come into view. [typeface] is the one the pages are laid out and drawn with. [highlight] is the
     * source range of the search hit last shown.
     */
    data class Reading(
        val title: String,
        val settings: GlobalSettings,
        val colors: PageColors,
        val typeface: TextTypeface,
        val frame: PageFrame?,
        val run: PageRun?,
        val current: Int,
        val pages: Map<Int, TextPage>,
        val number: PageNumber?,
        val contents: List<Heading>,
        val bookmarks: List<Bookmark>,
        val search: SearchState,
        val highlight: IntRange?,
    ) : TextReaderUiState {
        /** The indices in [run] of the shown pages, right to left. */
        val shown: IntRange
            get() = run?.let { current until minOf(it.size, current + (frame?.pagesPerView ?: 1)) } ?: IntRange.EMPTY

        /** Page [index] of [run], or null while it is laid out. */
        fun page(index: Int): TextPage? = run?.takeIf { index in 0 until it.size }?.let { pages[it.start(index)] }
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
    private val heapBudget: Long = textHeapBudget(),
    private val startAt: NeighborStart = NeighborStart.Saved,
    /** A turn that waited for the layout and then ran into an end of the text (docs/spec.md section 4.9). */
    private val onEdge: (BookEdge) -> Unit = {},
    /** The view changed by a turn, a jump or a step, and 「めくりで振動」 is on (docs/spec.md section 4.10). */
    private val onTurned: () -> Unit = {},
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
    private var setter: Typesetter? = null

    /** The pages around the current view, by start (docs/spec.md section 4.9). */
    private var window = emptyMap<Int, TextPage>()
    private var fillJob: Job? = null
    private var anchor = 0
    private var highlight: IntRange? = null
    private var search: SearchState = SearchState.Idle
    private var marks = emptyList<Bookmark>()
    private var readState = ReadState.Reading
    private var lastWritten: Pair<ReadState, Int>? = null

    /** The progress the text had when it opened, which 位置を戻して閉じる puts back. */
    private var before: BookProgress? = null
    private var restoring = false

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
        val result = when (val opened = opener.open()) {
            is TextOpenResult.Failed -> return fail(opened.reason)
            is TextOpenResult.Opened -> opened
        }
        val text = result.text
        if (closed) return text.close()
        opened = text
        val first = settings.first()
        val fallback = Charset.forName(first.fallbackCharset.charsetName)
        val parsed = read(result.bytes, fallback) ?: return
        document = parsed.first
        contents = parsed.second
        title = parsed.first.title ?: text.title
        // A reader closed moments ago may still hold this text's latest position in the writer's window.
        val openedAt = clock()
        writer.flush().join()
        val saved = progress.get(text.id)
        val length = parsed.first.sourceLength
        before = saved
        anchor = when (startAt) {
            NeighborStart.Saved -> ((saved?.position as? ReadingPosition.Text)?.charOffset ?: 0L).coerceIn(0L, length.toLong()).toInt()
            NeighborStart.First -> 0
            NeighborStart.Last -> maxOf(0, length - 1)
        }
        readState = if (saved?.state == ReadState.Read) ReadState.Read else ReadState.Reading
        lastWritten = saved?.let { it.state to ((it.position as? ReadingPosition.Text)?.charOffset?.toInt() ?: -1) }
        history.opened(text.id, text.book, openedAt, first.historyLimit)
        if (stopped) history.stop()
        scope.launch { bookmarks.inBook(text.id).collect { marks = it; render() } }
        scope.launch { settings.collect { apply(it) } }
    }

    /**
     * The document of [bytes] and its contents, or null when they do not fit [heapBudget] (docs/spec.md section
     * 3.11). The bytes go once decoded: each step is its own call, so nothing holds them while the text parses.
     */
    private suspend fun read(bytes: ByteArray, fallback: Charset): Pair<TextDocument, List<Heading>>? {
        if (TextFootprint.decoding(bytes.size) > heapBudget) {
            fail(TextFailure.TooLarge)
            return null
        }
        val source = working { decodeText(bytes, detectCharset(bytes, fallback).charset) } ?: return null
        return parse(source)
    }

    private suspend fun parse(source: String): Pair<TextDocument, List<Heading>>? {
        val fits = working { TextFootprint.reading(source) <= heapBudget } ?: return null
        if (!fits) {
            fail(TextFailure.TooLarge)
            return null
        }
        return working { parseAozora(source).let { it to tableOfContents(it) } }
    }

    /**
     * [block] on [work]. Running out of memory there ends the reading as too large and drops what it holds, so the
     * failure stays on screen and the memory comes back (docs/spec.md section 6.1).
     */
    private suspend fun <T> working(block: () -> T): T? = try {
        withContext(work) { block() }
    } catch (_: OutOfMemoryError) {
        fail(TextFailure.TooLarge)
        null
    }

    /** The reading ends here: what it holds goes, and so does the source, which nothing can read from again. */
    private fun fail(reason: TextFailure) {
        layoutJob?.cancel()
        fillJob?.cancel()
        searchJob?.cancel()
        document = null
        contents = emptyList()
        run = null
        setter = null
        window = emptyMap()
        pendingTurn = null
        search = SearchState.Idle
        highlight = null
        release()
        _state.value = TextReaderUiState.Failed(reason)
    }

    /** Stops the history and closes the source, once. */
    private fun release() {
        val text = opened ?: return
        opened = null
        history.stop()
        text.close()
    }

    fun onViewport(next: Viewport) {
        if (next == viewport) return
        viewport = next
        current?.let { reframe(it, typeface) }
    }

    /**
     * Turns [views] views forward, or back when negative. A turn onto a page not laid out yet waits for it, and a
     * turn past either end of the text moves nothing and says which end it ran into.
     */
    fun turn(views: Int): TurnResult {
        val run = run ?: return TurnResult.Waiting
        val perView = frame?.pagesPerView ?: 1
        val index = run.indexOf(anchor)
        val target = index + views * perView
        val from = run.start(index)
        return when {
            target < 0 && index > 0 -> TurnResult.Moved(from).also { moveTo(run.start(0)) }
            target < 0 && !run.fromStart || target > run.lastIndex && !run.complete -> TurnResult.Waiting.also { pendingTurn = views }
            target < 0 -> TurnResult.Edge(BookEdge.Start)
            target > run.lastIndex -> TurnResult.Edge(BookEdge.End)
            else -> TurnResult.Moved(from).also { moveTo(run.start(target)) }
        }
    }

    /** The page at [index] of the current run, as the continuous layout scrolls onto it. A scroll is not a turn. */
    fun showPage(index: Int) {
        val run = run?.takeIf { index in 0 until it.size } ?: return
        if (run.indexOf(anchor) != index) moveTo(run.start(index), scrolled = true)
    }

    fun first() = goTo(0)

    fun last() {
        val document = document ?: return
        val run = run
        if (run != null && run.complete) moveTo(run.start(run.lastIndex)) else goTo(maxOf(0, document.sourceLength - 1))
    }

    /** The page bar: [fraction] of the way through the text, by page once every page is laid out and by character before. */
    fun goToFraction(fraction: Float) {
        val document = document ?: return
        val run = run
        val f = fraction.coerceIn(0f, 1f)
        if (run != null && run.fromStart && run.complete) {
            moveTo(run.start(Math.round(f * run.lastIndex)))
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
            val result = working { searchText(document, query) } ?: return@launch
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

    /**
     * 位置を戻して閉じる (docs/spec.md section 4.8): the text gets back the position and state it had when it opened,
     * or none when it had none. The reader is closing, so nothing is written after it.
     */
    fun restorePosition() {
        val id = opened?.id ?: return
        restoring = true
        writer.submit(before ?: BookProgress(id, ReadState.Unread, null, clock()))
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
        fillJob?.cancel()
        searchJob?.cancel()
        writer.flush()
        release()
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
            // A page the run does not reach is another view than the one shown.
            if (run != null && current?.turnHaptics == true) onTurned()
            anchor = offset
            pendingTurn = null
            relayout()
        }
    }

    /** The source offset of the first page of the view shown, which names the view. */
    private fun viewStart(): Int? = run?.let { it.start(it.indexOf(anchor)) }

    private fun moveTo(offset: Int, keepHighlight: Boolean = false, scrolled: Boolean = false) {
        if (!keepHighlight) highlight = null
        val from = viewStart()
        anchor = offset
        pendingTurn = null
        val to = viewStart()
        if (!scrolled && from != null && to != null && TurnHaptics.due(current?.turnHaptics == true, from, to)) onTurned()
        fill()
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
        fillJob?.cancel()
        run = null
        setter = null
        window = emptyMap()
        val document = document
        val frame = frame
        laidOut = frame?.params?.let { it to typeface }
        render()
        if (document == null || frame == null) return
        val typeface = typeface
        layoutJob = scope.launch {
            val metrics = fonts.metrics(typeface)
            val setter = working { Typesetter(VerticalLayout(document, frame.params, metrics), document.sourceLength, anchor) } ?: return@launch
            this@TextReaderModel.setter = setter
            while (true) {
                val reading = anchor
                val (near, snapshot) = working { setter.step(reading)?.let { it to setter.snapshot() } } ?: break
                publish(snapshot, near)
            }
        }
    }

    private fun publish(snapshot: PageRun, near: List<TextPage>) {
        run = snapshot
        window = window + near.associateBy { it.start }
        val pending = pendingTurn
        pendingTurn = null
        if (pending != null) (turn(pending) as? TurnResult.Edge)?.let { onEdge(it.edge) }
        fill()
        render()
        record()
    }

    /** The starts of the pages to keep: the shown ones first, then the two after them and the two before. */
    private fun wanted(run: PageRun): List<Int> {
        val current = run.indexOf(anchor)
        val after = current + (frame?.pagesPerView ?: 1)
        val order = (current until after + KEPT_AROUND) + (current - 1 downTo current - KEPT_AROUND)
        return order.filter { it in 0 until run.size }.map(run::start)
    }

    /**
     * Drops the kept pages that left the view's surroundings and lays out the ones that came in, on [work] between
     * the steps of the layout. One fill runs at a time and picks up each new position as it goes.
     */
    private fun fill() {
        val run = run ?: return
        val wanted = wanted(run)
        window = window.filterKeys { it in wanted }
        if (fillJob?.isActive == true) return
        fillJob = scope.launch {
            while (true) {
                val typesetter = setter ?: break
                val start = this@TextReaderModel.run?.let(::wanted)?.firstOrNull { it !in window } ?: break
                val page = working { typesetter.page(start) } ?: break
                if (this@TextReaderModel.run?.let(::wanted)?.contains(start) == true) window = window + (start to page)
                render()
            }
        }
    }

    /** Hands the position to the writer when it or the read state changed (docs/spec.md section 2.7). */
    private fun record() {
        if (restoring) return
        val run = run ?: return
        val id = opened?.id ?: return
        val position = position() ?: return
        val index = run.indexOf(anchor)
        if (run.complete && index + (frame?.pagesPerView ?: 1) > run.lastIndex) readState = ReadState.Read
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
            pages = window,
            number = run?.number(index),
            contents = contents,
            bookmarks = marks,
            search = search,
            highlight = highlight,
        )
    }
}

/** The pages kept on each side of the shown ones (docs/spec.md section 4.9). */
private const val KEPT_AROUND = 2

/** The Java heap a text may take as it opens: half of it, which leaves the rest of the app its room. */
internal fun textHeapBudget(): Long = Runtime.getRuntime().maxMemory() / 2
