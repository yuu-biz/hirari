package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.entryBookId
import io.github.yuubiz.hirari.core.model.locationBookId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModelTest {
    private val fixture = Fixture()
    private val place = LibraryLocation.SafTree(LocationId(1), "漫画", UriString("content://tree/manga"))
    private val cp932 = Charset.forName("windows-31j")
    private val utf8 = Charsets.UTF_8
    private val id = locationBookId(place, "作品/book.zip")
    private val contents = FakeContents(
        mapOf(
            cp932 to listOf("第2話.jpg", "第1話.jpg", "あとがき.txt", "第10話.jpg"),
            utf8 to listOf("c.jpg", "b.jpg", "x.txt", "a.jpg"),
        ),
        detected = cp932,
    )

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): ArchiveViewModel {
        fixture.locations.rows.value = listOf(place)
        fixture.archives.result = ArchiveOpenResult.Opened(contents)
        return ArchiveViewModel(CompletableDeferred(fixture.services), ArchiveKey(place.id, "作品/book.zip", "doc:book"), UnconfinedTestDispatcher(testScheduler))
            .also { watch(it.state) }
    }

    private fun ArchiveViewModel.shown() = assertIs<ArchiveUi.Shown>(state.value.content)

    @Test
    fun `the list shows the texts first and the pages in natural order of the detected names`() = runTest {
        val list = viewModel()
        val shown = list.shown()

        assertEquals(listOf("作品/book.zip" to id), fixture.archives.opened)
        assertEquals(listOf("あとがき.txt"), shown.texts.map { it.text.name })
        assertEquals(entryBookId(id, byteArrayOf(2)), shown.texts.single().text.id)
        assertEquals(listOf("第1話.jpg", "第2話.jpg", "第10話.jpg"), shown.pages.map { it.page.name })
        assertEquals(listOf(0, 1, 2), shown.pages.map { it.page.readerIndex })
        assertEquals(cp932, shown.charset)
        assertEquals(null, shown.chosen)
    }

    @Test
    fun `choosing another charset sorts the pages anew, is kept for the book, and taps still open the right page`() = runTest {
        val list = viewModel()
        list.chooseCharset(utf8)

        val shown = list.shown()
        assertEquals(utf8, fixture.books.overrides.value[id]?.nameCharset)
        assertEquals(utf8, shown.chosen)
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), shown.pages.map { it.page.name })
        assertEquals(listOf("x.txt"), shown.texts.map { it.text.name })
        assertEquals(
            ArchiveTarget.Page(OpenBookRequest(BookAddress.InLocation(place.id, "作品/book.zip", folder = false, "doc:book"), StartAt.Page(2))),
            list.open(shown.pages.first().page),
        )

        list.chooseCharset(null)
        assertEquals(listOf("第1話.jpg", "第2話.jpg", "第10話.jpg"), list.shown().pages.map { it.page.name })
    }

    @Test
    fun `pages before the stored position fade and marking a page or a text writes their progress`() = runTest {
        val list = viewModel()
        list.readUpTo(list.shown().pages[1].page)

        assertEquals(ReadingPosition.Image(1, 3), fixture.books.progress.value[id]?.position)
        assertEquals(listOf(true, false, false), list.shown().pages.map { it.read })

        list.readUpTo(list.shown().pages[2].page)
        assertEquals(ReadState.Read, fixture.books.progress.value[id]?.state)
        assertTrue(list.shown().pages.all { it.read })

        val text = list.shown().texts.single()
        list.mark(text.text, ReadState.Read)
        assertEquals(ReadMark.Read, list.shown().texts.single().mark)
    }

    @Test
    fun `a text opens as an entry of the archive document`() = runTest {
        val list = viewModel()
        assertEquals(
            ArchiveTarget.Text(TextAddress.ArchiveEntry(UriString("content://tree/manga/document/book.zip"), 2)),
            list.open(list.shown().texts.single().text),
        )
    }

    @Test
    fun `an archive that does not open shows why`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.archives.result = ArchiveOpenResult.Failed(ArchiveFailure.Encrypted)
        val list = ArchiveViewModel(CompletableDeferred(fixture.services), ArchiveKey(place.id, "作品/book.zip"), UnconfinedTestDispatcher(testScheduler))
            .also { watch(it.state) }
        assertEquals(ArchiveUi.Failed(ArchiveFailure.Encrypted), list.state.value.content)
    }
}
