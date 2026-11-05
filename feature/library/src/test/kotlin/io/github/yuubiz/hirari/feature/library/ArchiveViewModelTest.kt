package io.github.yuubiz.hirari.feature.library

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.data.ProgressRepository
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.EntryOrder
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    private fun TestScope.viewModel(compute: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)): ArchiveViewModel {
        fixture.locations.rows.value = listOf(place)
        fixture.archives.result = ArchiveOpenResult.Opened(contents)
        return ArchiveViewModel(CompletableDeferred(fixture.services), ArchiveKey(place.id, "作品/book.zip", "doc:book"), compute)
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
    fun `changing the chosen charset remakes only this books automatic cover at every size even without progress`() = runTest {
        fixture.coverBooks.folders["作品"] = listOf(FolderEntry("book.zip", "doc:book", false, 500, T0))
        val list = viewModel()

        list.chooseCharset(utf8)
        assertEquals(3, fixture.thumbnails.regenerated.size)
        val remade = fixture.thumbnails.regenerated.map {
            assertIs<io.github.yuubiz.hirari.core.image.thumbnail.ThumbSubject.Book>(it.subject)
        }
        assertEquals(setOf(id), remade.map { it.id }.toSet())
        assertTrue(remade.all { it.coverEntry == null })
        // Re-selecting the same choice keeps the existing cover; returning to auto remakes it again.
        list.chooseCharset(utf8)
        assertEquals(3, fixture.thumbnails.regenerated.size)
        list.chooseCharset(null)
        assertEquals(6, fixture.thumbnails.regenerated.size)
    }

    @Test
    fun `changing the chosen charset keeps an explicitly selected cover`() = runTest {
        fixture.coverBooks.folders["作品"] = listOf(FolderEntry("book.zip", "doc:book", false, 500, T0))
        fixture.books.put(book(place.id, "作品/book.zip").copy(id = id))
        fixture.books.setCover(id, 1)
        val list = viewModel()
        list.chooseCharset(utf8)
        assertEquals(utf8, fixture.books.overrides.value[id]?.nameCharset)
        assertTrue(fixture.thumbnails.regenerated.isEmpty())
    }

    @Test
    fun `a book whose order the reader reversed lists its pages in that order`() = runTest {
        val list = viewModel()
        fixture.books.updateOverrides(id) { it.copy(reverseOrder = true) }

        val shown = list.shown()
        assertEquals(listOf("第10話.jpg", "第2話.jpg", "第1話.jpg"), shown.pages.map { it.page.name })
        assertEquals(listOf(0, 1, 2), shown.pages.map { it.page.index })
    }

    @Test
    fun `the stored entry order lists the pages as the archive holds them, also when changed with the list open`() = runTest {
        val list = viewModel()
        fixture.settings.value.value = fixture.settings.value.value.copy(entryOrder = EntryOrder.Stored)

        val shown = list.shown()
        assertEquals(listOf("第2話.jpg", "第1話.jpg", "第10話.jpg"), shown.pages.map { it.page.name })
        assertEquals(listOf(0, 1, 2), shown.pages.map { it.page.index })
        fixture.books.updateOverrides(id) { it.copy(reverseOrder = true) }
        assertEquals(listOf("第10話.jpg", "第1話.jpg", "第2話.jpg"), list.shown().pages.map { it.page.name })
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
    fun `charsets chosen before the last change ends move the stored position one change at a time`() = runTest {
        val list = viewModel(StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        list.readUpTo(list.shown().pages[0].page)
        advanceUntilIdle()

        list.chooseCharset(utf8)
        list.chooseCharset(null)
        advanceUntilIdle()
        assertEquals(null, fixture.books.overrides.value[id]?.nameCharset)
        assertEquals(ReadingPosition.Image(0, 3), fixture.books.progress.value[id]?.position)
    }

    @Test
    fun `leaving the screen once the new charset is stored still moves the position and remakes the automatic cover`() = runTest {
        fixture.coverBooks.folders["作品"] = listOf(FolderEntry("book.zip", "doc:book", false, 500, T0))
        val reading = CompletableDeferred<Unit>()
        val stored = fixture.books.progressRepository
        val progress = object : ProgressRepository by stored {
            override suspend fun get(book: BookId): BookProgress? {
                reading.await()
                return stored.get(book)
            }
        }
        val services = with(fixture) {
            LibraryServices(locations, books, history, bookmarks, progress, settings, grants, lister, smb, archives, launch, coverIndex, covers, saves = saves, appScope = fixture.services.appScope)
        }
        fixture.locations.rows.value = listOf(place)
        fixture.archives.result = ArchiveOpenResult.Opened(contents)
        val list = ArchiveViewModel(CompletableDeferred(services), ArchiveKey(place.id, "作品/book.zip", "doc:book"), UnconfinedTestDispatcher(testScheduler))
            .also { watch(it.state) }
        list.readUpTo(list.shown().pages[0].page)

        list.chooseCharset(utf8)
        list.viewModelScope.cancel()
        reading.complete(Unit)
        advanceUntilIdle()
        assertEquals(utf8, fixture.books.overrides.value[id]?.nameCharset)
        assertEquals(ReadingPosition.Image(1, 3), fixture.books.progress.value[id]?.position)
        assertEquals(3, fixture.thumbnails.regenerated.size)
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
    fun `a text opens as an entry of the archive in its place with the id the list marks the archive by`() = runTest {
        val list = viewModel()
        assertEquals(
            ArchiveTarget.Text(TextAddress.ArchiveEntry(ArchiveFile.InLocation(place.id, "作品/book.zip"), 2, id)),
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
            ArchiveTarget.Text(TextAddress.ArchiveEntry(ArchiveFile.InLocation(server.id, "作品/book.zip"), 2, locationBookId(server, "作品/book.zip"))),
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
        assertEquals(ArchiveUi.Loading, list.state.value)

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
        assertEquals(386, refs.first().sizePx)
    }

    @Test
    fun `without the entry of the archive in its folder the pages keep their numbered squares`() = runTest {
        assertEquals(null, viewModel().shown().covers)
    }

    @Test
    fun `a page chosen as the cover of the folder just before leaving the screen is still remade`() = runTest {
        fixture.coverBooks.folders[""] = listOf(FolderEntry("作品", "doc:作品", directory = true, size = null, modified = at))
        fixture.coverBooks.folders["作品"] = listOf(FolderEntry("book.zip", "doc:book", directory = false, size = 500, modified = at))
        fixture.locations.rows.value = listOf(place)
        fixture.archives.result = ArchiveOpenResult.Opened(contents)
        val screen = ViewModelStore()
        val list = screen.own { ArchiveViewModel(CompletableDeferred(fixture.services), ArchiveKey(place.id, "作品/book.zip"), UnconfinedTestDispatcher(testScheduler)) }
        watch(list.state)
        val page = list.shown().pages[1].page
        val slow = CompletableDeferred<Unit>()
        fixture.coverBooks.gate = slow

        list.chooseFolderCover(page, choose = true)
        screen.clear()
        slow.complete(Unit)
        assertEquals(3, fixture.thumbnails.regenerated.size)
        assertEquals(setOf(page.entryIndex), fixture.thumbnails.regenerated.map { (it.subject as io.github.yuubiz.hirari.core.image.thumbnail.ThumbSubject.Folder).chosen?.coverEntry }.toSet())
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
