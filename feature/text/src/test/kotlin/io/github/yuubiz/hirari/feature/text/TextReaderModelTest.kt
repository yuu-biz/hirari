package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.data.ProgressWriter
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.TextLayout
import io.github.yuubiz.hirari.core.model.TextTypeface
import io.github.yuubiz.hirari.core.text.FixedGlyphMetrics
import io.github.yuubiz.hirari.core.text.TextPage
import io.github.yuubiz.hirari.core.text.VerticalLayout
import io.github.yuubiz.hirari.core.text.parseAozora
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TextReaderModelTest {
    private val id = BookId("text")
    private val source = sampleText()
    private val progress = FakeProgress()
    private val bookmarks = FakeBookmarks()
    private val history = FakeHistory()
    private val settings = MutableStateFlow(GlobalSettings(textSizeSp = 20, textLayout = TextLayout.Single))
    private val screen = Viewport(width = 400, height = 600, density = 1f)
    private var closed = 0

    private fun TestScope.model(
        text: String = source,
        fonts: TextFonts = FakeFonts(),
        bytes: ByteArray = text.toByteArray(Charsets.UTF_8),
        heapBudget: Long = textHeapBudget(),
    ): TextReaderModel {
        val opened = OpenedText(id, "file.txt", "dir/file.txt", book = null, pictures = null) { closed++ }
        return TextReaderModel(
            scope = backgroundScope,
            work = StandardTestDispatcher(testScheduler),
            opener = { TextOpenResult.Opened(opened, bytes) },
            settings = settings,
            progress = progress,
            writer = ProgressWriter(progress, backgroundScope),
            bookmarks = bookmarks,
            fonts = fonts,
            history = history,
            clock = { Instant.ofEpochSecond(testScheduler.currentTime) },
            heapBudget = heapBudget,
        ).also {
            it.start()
            it.onViewport(screen)
        }
    }

    /** Background work, as the model's, never makes the scheduler idle, so time moves on instead. */
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private val TextReaderModel.reading: TextReaderUiState.Reading get() = assertIs<TextReaderUiState.Reading>(state.value)

    private val TextReaderModel.page: TextPage get() = reading.let { assertNotNull(it.page(it.current), "page ${it.current} is laid out") }

    /** The pages of [text] laid out whole from its start, as a reader that opens at the start lays them out. */
    private fun pagesOf(text: String = source): List<TextPage> =
        VerticalLayout(parseAozora(text), pageFrame(screen, settings.value).params, FixedGlyphMetrics).pages().toList()

    @Test
    fun `a text opens on its first page with the first line as the title and every page laid out`() = runTest {
        val model = model()
        settle()
        val state = model.reading
        assertEquals("猫の本", state.title)
        assertEquals(0, state.current)
        assertEquals(0, model.page.start)
        val run = state.run!!
        assertTrue(run.fromStart && run.complete && run.size > 8, "${run.size} pages")
        assertEquals(PageNumber(1, run.size, currentExact = true, totalExact = true), state.number)
        assertEquals(source.length, run.end(run.lastIndex))
        assertEquals(listOf(id), history.opened.map { it.first })
    }

    @Test
    fun `turning moves one page each way and stops at both ends`() = runTest {
        val model = model()
        settle()
        val pages = pagesOf()
        assertEquals(TurnResult.Edge(BookEdge.Start), model.turn(-1))
        assertEquals(0, model.reading.current)
        assertEquals(TurnResult.Moved(pages[0].start), model.turn(1))
        model.turn(1)
        assertEquals(pages[2], model.page)
        assertEquals(TurnResult.Moved(pages[2].start), model.turn(-1))
        assertEquals(pages[1], model.page)
        model.last()
        settle()
        assertEquals(pages.last(), model.page)
        assertEquals(TurnResult.Edge(BookEdge.End), model.turn(1))
        assertEquals(pages.last(), model.page)
    }

    @Test
    fun `a spread shows two pages and turns by two`() = runTest {
        settings.value = settings.value.copy(textLayout = TextLayout.Spread)
        val model = model()
        settle()
        val pages = pagesOf()
        val shown = { model.reading.let { state -> state.shown.map(state::page) } }
        assertEquals(pages.subList(0, 2), shown())
        model.turn(1)
        assertEquals(pages.subList(2, 4), shown())
    }

    @Test
    fun `a new text size keeps the sentence being read on screen, deep in a paragraph longer than a page`() = runTest {
        val model = model()
        settle()
        repeat(14) { model.turn(1) }
        settle()
        val reading = model.page
        val paragraphStart = source.indexOf("　", source.indexOf("第2章"))
        assertTrue(reading.start > paragraphStart + 600, "the page must sit well inside its paragraph")
        for (size in listOf(28, 14, 20, 40)) {
            settings.value = settings.value.copy(textSizeSp = size)
            settle()
            assertTrue(reading.start in model.page, "size $size shows ${model.page.start} until ${model.page.end}, not ${reading.start}")
        }
    }

    @Test
    fun `a saved position opens on its page, and an unchanged position is not written again`() = runTest {
        val offset = source.indexOf("名前はまだ3章の10 番目")
        progress.stored.value = mapOf(id to BookProgress(id, ReadState.Reading, ReadingPosition.Text(offset.toLong(), source.length.toLong()), Instant.EPOCH))
        val model = model()
        settle()
        assertTrue(offset in model.page)
        assertEquals(emptyList(), progress.writes)
    }

    @Test
    fun `turning writes the position and the last page marks the text read`() = runTest {
        val model = model()
        settle()
        assertEquals(listOf(ReadState.Reading to 0L), progress.writes.map { it.state to (it.position as ReadingPosition.Text).charOffset })
        model.turn(1)
        model.onStop()
        settle()
        assertEquals(model.page.start.toLong(), (progress.stored.value.getValue(id).position as ReadingPosition.Text).charOffset)
        assertEquals(1, history.stops)
        model.last()
        model.onStop()
        settle()
        assertEquals(ReadState.Read, progress.stored.value.getValue(id).state)
        model.first()
        model.onStop()
        settle()
        assertEquals(ReadState.Read, progress.stored.value.getValue(id).state, "a read text stays read")
    }

    @Test
    fun `the contents come from the headings and jump to their page`() = runTest {
        val model = model()
        settle()
        val contents = model.reading.contents
        assertEquals(listOf("第1章", "第2章", "第3章", "第4章"), contents.map { it.text })
        model.goTo(contents[2])
        settle()
        assertTrue(contents[2].start in model.page)
    }

    @Test
    fun `search folds width and case, skips ruby, and shows a hit with its highlight`() = runTest {
        val model = model("題\nＡＢＣの話。吾輩《わがはい》は猫。\n" + source.substringAfter('\n'))
        settle()
        model.search("abc")
        settle()
        val result = (model.reading.search as SearchState.Done).result
        assertEquals(1, result.hits.size)
        val hit = result.hits.single()
        assertEquals("ＡＢＣ", hit.context.substring(hit.matchStart, hit.matchEnd))
        model.search("わがはい")
        settle()
        assertEquals(emptyList(), (model.reading.search as SearchState.Done).result.hits)
        model.search("4章の50")
        settle()
        val far = (model.reading.search as SearchState.Done).result.hits.single()
        model.show(far)
        settle()
        assertEquals(far.start until far.end, model.reading.highlight)
        assertTrue(far.start in model.page)
        model.turn(1)
        assertEquals(null, model.reading.highlight)
    }

    @Test
    fun `a bookmark keeps the reading position and takes the reader back to it`() = runTest {
        val model = model()
        settle()
        repeat(5) { model.turn(1) }
        settle()
        val page = model.page
        model.addBookmark()
        settle()
        val mark = model.reading.bookmarks.single()
        assertEquals(ReadingPosition.Text(page.start.toLong(), source.length.toLong()), mark.position)
        assertEquals("${page.start.toLong() * 100 / source.length}%", mark.label)
        model.first()
        model.goTo(mark)
        settle()
        assertEquals(page, model.page)
        model.deleteBookmark(mark)
        settle()
        assertEquals(emptyList(), model.reading.bookmarks)
    }

    @Test
    fun `an imported font that is gone falls back to Mincho`() = runTest {
        settings.value = settings.value.copy(textTypeface = TextTypeface.Imported("lost.ttf"))
        val model = model(fonts = FakeFonts(gone = setOf("lost.ttf")))
        settle()
        assertEquals(TextTypeface.Mincho, model.reading.typeface)
    }

    @Test
    fun `a failed open shows why, and closing releases the text`() = runTest {
        val failing = TextReaderModel(
            backgroundScope, StandardTestDispatcher(testScheduler), { TextOpenResult.Failed(TextFailure.NotFound) },
            settings, progress, ProgressWriter(progress, backgroundScope), bookmarks, FakeFonts(), history,
        )
        failing.start()
        settle()
        assertEquals(TextReaderUiState.Failed(TextFailure.NotFound), failing.state.value)
        val model = model()
        settle()
        model.close()
        assertEquals(1, closed)
    }

    @Test
    fun `a 60 MiB text on one line fails as too large instead of running out of memory`() = runTest {
        val model = model(bytes = ByteArray(60 shl 20) { 'a'.code.toByte() })
        settle()
        assertEquals(TextReaderUiState.Failed(TextFailure.TooLarge), model.state.value)
        assertEquals(1, closed)
        model.close()
        assertEquals(1, closed)
    }

    @Test
    fun `a text whose lines take more memory than texts may have fails as too large, where a longer one opens`() = runTest {
        val budget = 16L shl 20
        val long = sampleText(sentencesPerChapter = 1000)
        val opens = model(long, heapBudget = budget)
        settle()
        assertIs<TextReaderUiState.Reading>(opens.state.value)
        val blank = "\n".repeat(70_000)
        assertTrue(blank.length < long.length)
        val fails = model(blank, heapBudget = budget)
        settle()
        assertEquals(TextReaderUiState.Failed(TextFailure.TooLarge), fails.state.value)
    }

    @Test
    fun `a text of five million characters lays out to its end within the heap`() = runTest {
        val big = sampleText(chapters = 48, sentencesPerChapter = 3500)
        assertTrue(big.length > 5_000_000, "${big.length}")
        val model = model(big)
        settle()
        val run = model.reading.run!!
        assertTrue(run.fromStart && run.complete)
        model.last()
        settle()
        assertEquals(big.length, model.page.end)
    }

    @Test
    fun `only the pages around the view are kept, and a page that comes back is the page it was`() = runTest {
        val pages = pagesOf()
        val model = model()
        settle()
        repeat(6) { model.turn(1) }
        settle()
        val state = model.reading
        assertEquals(6, state.current)
        assertEquals((4..8).map(pages::get), (4..8).map(state::page))
        assertEquals(listOf(null, null), listOf(state.page(3), state.page(9)))
        assertEquals(5, state.pages.size)
        model.first()
        settle()
        assertEquals(pages.take(3), (0..2).map(model.reading::page))
        model.last()
        settle()
        assertEquals(pages.last(), model.page)
        assertEquals(PageNumber(pages.size, pages.size, currentExact = true, totalExact = true), model.reading.number)
    }

    @Test
    fun `running out of memory while laying out fails as too large, and a new size does not bring the text back`() = runTest {
        val model = model(fonts = FakeFonts(metrics = { _, _, _ -> throw OutOfMemoryError() }))
        settle()
        assertEquals(TextReaderUiState.Failed(TextFailure.TooLarge), model.state.value)
        assertEquals(1, closed)
        assertEquals(1, history.stops)
        settings.value = settings.value.copy(textSizeSp = 30)
        settle()
        assertEquals(TextReaderUiState.Failed(TextFailure.TooLarge), model.state.value)
        model.close()
        assertEquals(1, closed)
        assertEquals(1, history.stops)
    }

    @Test
    fun `a text too large for the heap after it is read releases its source at once`() = runTest {
        val model = model(sampleText(), heapBudget = 1L shl 20)
        settle()
        assertEquals(TextReaderUiState.Failed(TextFailure.TooLarge), model.state.value)
        assertEquals(1, closed)
        model.close()
        assertEquals(1, closed)
    }
}
