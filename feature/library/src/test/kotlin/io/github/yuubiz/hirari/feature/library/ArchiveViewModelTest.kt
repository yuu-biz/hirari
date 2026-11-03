package io.github.yuubiz.hirari.feature.library

import androidx.lifecycle.ViewModelStore
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
    private val names = mapOf(
        cp932 to listOf("第2話.jpg", "第1話.jpg", "あとがき.txt", "第10話.jpg"),
        utf8 to listOf("c.jpg", "b.jpg", "x.txt", "a.jpg"),
    )
    private val contents = FakeContents(names, detected = cp932)

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

    private fun ArchiveViewModel.shown() = assertIs<ArchiveUi.Shown>(state.value)

    @Test
    fun `the list shows the texts first and the pages in natural order of the detected names`() = runTest {
        val list = viewModel()
        val shown = list.shown()

        assertEquals(listOf("作品/book.zip" to id), fixture.archives.opened)
        assertEquals(listOf("あとがき.txt"), shown.texts.map { it.text.name })
        assertEquals(entryBookId(id, byteArrayOf(2)), shown.texts.single().text.id)
        assertEquals(listOf("第1話.jpg", "第2話.jpg", "第10話.jpg"), shown.pages.map { it.page.name })
        assertEquals(listOf(0, 1, 2), shown.pages.map { it.page.index })
        assertEquals(cp932, shown.charset)
        assertEquals(null, shown.chosen)
    }

    @Test
    fun `choosing another charset sorts the pages anew, is kept for the book, and a tap opens the reader at the place the list shows`() = runTest {
        val list = viewModel()
        list.chooseCharset(utf8)

        val shown = list.shown()
        assertEquals(utf8, fixture.books.overrides.value[id]?.nameCharset)
        assertEquals(utf8, shown.chosen)
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), shown.pages.map { it.page.name })
        assertEquals(listOf("x.txt"), shown.texts.map { it.text.name })
        assertEquals(
            ArchiveTarget.Page(OpenBookRequest(BookAddress.InLocation(place.id, "作品/book.zip", folder = false, "doc:book"), StartAt.Page(1))),
            list.open(shown.pages[1].page),
        )

        list.chooseCharset(null)
        assertEquals(listOf("第1話.jpg", "第2話.jpg", "第10話.jpg"), list.shown().pages.map { it.page.name })
    }

    @Test
    fun `the stored position follows its page when another charset reorders the pages`() = runTest {
        val list = viewModel()
        list.readUpTo(list.shown().pages[0].page)
        assertEquals(ReadingPosition.Image(0, 3), fixture.books.progress.value[id]?.position)

        list.chooseCharset(utf8)
        assertEquals(ReadingPosition.Image(1, 3), fixture.books.progress.value[id]?.position)
        assertEquals(listOf(true, false, false), list.shown().pages.map { it.read })

        list.chooseCharset(null)
        assertEquals(ReadingPosition.Image(0, 3), fixture.books.progress.value[id]?.position)
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
    fun `a text opens as an entry of the archive document with the id the list marks the archive by`() = runTest {
        val list = viewModel()
        assertEquals(
            ArchiveTarget.Text(TextAddress.ArchiveEntry(ArchiveFile.Document(UriString("content://tree/manga/document/book.zip")), 2, id)),
            list.open(list.shown().texts.single().text),
        )
    }

    @Test
    fun `a page of an SMB archive opens the reader there and a text opens by the place and the path of the archive`() = runTest {
        val server = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "漫画", null, null, null)
        fixture.locations.rows.value = listOf(server)
        fixture.archives.result = ArchiveOpenResult.Opened(FakeContents(names, detected = cp932, documentUri = null))
        val list = ArchiveViewModel(CompletableDeferred(fixture.services), ArchiveKey(server.id, "作品/book.zip"), UnconfinedTestDispatcher(testScheduler))
            .also { watch(it.state) }
        val shown = list.shown()

        assertEquals(
            ArchiveTarget.Page(OpenBookRequest(BookAddress.InLocation(server.id, "作品/book.zip", folder = false), StartAt.Page(1))),
            list.open(shown.pages[1].page),
        )
        assertEquals(
            ArchiveTarget.Text(TextAddress.ArchiveEntry(ArchiveFile.OnServer(server.id, "作品/book.zip"), 2, locationBookId(server, "作品/book.zip"))),
            list.open(shown.texts.single().text),
        )
    }

    @Test
    fun `an archive that does not open shows why`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.archives.result = ArchiveOpenResult.Failed(ArchiveFailure.Encrypted)
        val list = ArchiveViewModel(CompletableDeferred(fixture.services), ArchiveKey(place.id, "作品/book.zip"), UnconfinedTestDispatcher(testScheduler))
            .also { watch(it.state) }
        assertEquals(ArchiveUi.Failed(ArchiveFailure.Encrypted), list.state.value)
    }

    @Test
    fun `leaving while the folder of the archive is still read closes the opened archive`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.archives.result = ArchiveOpenResult.Opened(contents)
        fixture.coverBooks.gate = CompletableDeferred()
        val screen = ViewModelStore()
        val list = screen.own { ArchiveViewModel(CompletableDeferred(fixture.services), ArchiveKey(place.id, "作品/book.zip"), UnconfinedTestDispatcher(testScheduler)) }
        watch(list.state)
        assertEquals(ArchiveUi.Loading, list.state.value.content)

        screen.clear()
        assertTrue(contents.closed)
    }

    @Test
    fun `an archive whose opening ends after the screen was left is closed`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.archives.result = ArchiveOpenResult.Opened(contents)
        val opening = CompletableDeferred<Unit>()
        fixture.archives.gate = opening
        val screen = ViewModelStore()
        val list = screen.own { ArchiveViewModel(CompletableDeferred(fixture.services), ArchiveKey(place.id, "作品/book.zip"), UnconfinedTestDispatcher(testScheduler)) }
        watch(list.state)

        screen.clear()
        opening.complete(Unit)
        assertTrue(contents.closed)
    }

    private val at = java.time.Instant.parse("2026-02-03T04:05:06Z")

    @Test
    fun `the pages get the thumbnail of the archive with each page as its cover`() = runTest {
        fixture.coverBooks.folders["作品"] = listOf(FolderEntry("book.zip", "doc:book", directory = false, size = 500, modified = at))
        fixture.coverIndex.directions.value = mapOf(id to io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight)
        val list = viewModel()
        val covers = checkNotNull(list.shown().covers)

        val refs = list.shown().pages.map { checkNotNull(covers.ref(it.page)) }
        assertEquals(listOf(1, 0, 3), refs.map { (it.spec as CoverSpec.Book).coverEntry })
        val spec = refs.first().spec as CoverSpec.Book
        assertEquals(id, spec.id)
        assertEquals(at, spec.modified)
        assertEquals(500, spec.byteSize)
        assertEquals(io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight, spec.direction)
        assertEquals(CoverOrigin.Archive(place, "作品/book.zip", "doc:book"), spec.origin)
        assertEquals(320, refs.first().sizePx)
    }

    @Test
    fun `without the entry of the archive in its folder the pages keep their numbered squares`() = runTest {
        assertEquals(null, viewModel().shown().covers)
    }

    @Test
    fun `choosing a page as the cover of the folder saves it and remakes the folder`() = runTest {
        fixture.coverBooks.folders[""] = listOf(FolderEntry("作品", "doc:作品", directory = true, size = null, modified = at))
        fixture.coverBooks.folders["作品"] = listOf(FolderEntry("book.zip", "doc:book", directory = false, size = 500, modified = at))
        val list = viewModel()
        assertTrue(list.shown().inFolder)
        val page = list.shown().pages[1].page

        list.chooseFolderCover(page, choose = true)
        assertEquals(
            listOf(Triple<LocationId, String, io.github.yuubiz.hirari.core.data.FolderCover?>(place.id, "作品", io.github.yuubiz.hirari.core.data.FolderCover("作品/book.zip", page.entryIndex))),
            fixture.locations.chosenCovers,
        )
        val folder = assertIs<io.github.yuubiz.hirari.core.image.thumbnail.ThumbSubject.Folder>(fixture.thumbnails.regenerated.first().subject)
        assertEquals(page.entryIndex, folder.chosen?.coverEntry)
        assertEquals(3, fixture.thumbnails.regenerated.size)

        assertEquals(page.entryIndex, list.shown().folderCoverPage)

        fixture.locations.chosenCovers.clear()
        list.chooseFolderCover(page, choose = false)
        assertEquals(null, fixture.locations.chosenCovers.single().third)
        assertEquals(null, list.shown().folderCoverPage)
    }
}
