package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.BookmarkGroup
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.TextEntry
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.HistoryEntry
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val fixture = Fixture()
    private val place = LibraryLocation.SafTree(LocationId(1), "漫画", UriString("content://tree/manga"))

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun viewModel() = HomeViewModel(CompletableDeferred(fixture.services))

    @Test
    fun `text bookmarks dispatch to a text address with their character offset`() = runTest {
        val text = book(place.id, "novel.txt").copy(format = BookFormat.Text(null))
        val server = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas", 445, "books", "", null, null, null)
        fixture.locations.rows.value = listOf(place, server)
        val home = viewModel()
        watch(home.state)
        val external = BookSource.External(UriString("content://books/novel.txt"))
        val entry = TextEntry(BookId("archive"), 3)
        val cases = listOf(
            text to TextAddress.InLocation(place.id, "novel.txt", startOffset = 120),
            text.copy(source = BookSource.InLocation(server.id, "novel.txt")) to TextAddress.InLocation(server.id, "novel.txt", startOffset = 120),
            text.copy(source = external) to TextAddress.Document(external.uri, startOffset = 120),
            text.copy(source = BookSource.InLocation(place.id, "novels.zip"), textEntry = entry) to
                TextAddress.ArchiveEntry(ArchiveFile.InLocation(place.id, "novels.zip"), 3, entry.archiveId, 120),
            text.copy(source = BookSource.InLocation(server.id, "novels.zip"), textEntry = entry) to
                TextAddress.ArchiveEntry(ArchiveFile.InLocation(server.id, "novels.zip"), 3, entry.archiveId, 120),
            text.copy(source = external, textEntry = entry) to
                TextAddress.ArchiveEntry(ArchiveFile.Document(external.uri), 3, entry.archiveId, 120),
        )
        for ((book, address) in cases) {
            assertEquals(HomeTarget.Text(address), home.request(BookmarkGroup(book.id, book, null, emptyList()), ReadingPosition.Text(120, 1000)))
        }
    }

    @Test
    fun `text bookmarks explain a missing book or an unavailable place like image bookmarks`() = runTest {
        fixture.locations.rows.value = listOf(place.copy(permissionHeld = false))
        val home = viewModel()
        watch(home.state)
        val text = book(place.id, "novels.zip").copy(format = BookFormat.Text(null), textEntry = TextEntry(BookId("archive"), 3))
        val position = ReadingPosition.Text(120, 1000)
        assertNull(home.request(BookmarkGroup(text.id, null, null, emptyList()), position))
        assertEquals(Notice.BookMissing, home.state.value.notice)
        assertNull(home.request(BookmarkGroup(text.id, text, null, emptyList()), position))
        assertEquals(Notice.PlaceUnavailable, home.state.value.notice)
        val removed = text.copy(source = BookSource.InLocation(LocationId(99), "novels.zip"))
        assertNull(home.request(BookmarkGroup(removed.id, removed, null, emptyList()), position))
        assertEquals(Notice.PlaceUnavailable, home.state.value.notice)
    }

    @Test
    fun `the shelf lists books being read with their progress and leaves out finished ones`() = runTest {
        val first = book(place.id, "作品/1巻.zip")
        val second = book(place.id, "作品/2巻.zip")
        fixture.locations.rows.value = listOf(place)
        fixture.books.put(first, second)
        fixture.books.put(reading(first, viewStart = 4), reading(second, viewStart = 9, state = ReadState.Read))
        val home = viewModel()
        watch(home.state)

        val shelf = (home.state.value.shelf as Loadable.Ready).value
        assertEquals(listOf(first to 50), shelf.continueReading.map { it.book.book to it.percent })
        assertEquals(listOf<LibraryLocation>(place), shelf.places)
    }

    @Test
    fun `choosing a history order stores it and the tab follows`() = runTest {
        val older = book(place.id, "a.zip")
        val newer = book(place.id, "b.zip")
        fixture.books.put(older, newer)
        fixture.history.entries.value = listOf(HistoryEntry(older.id, T0), HistoryEntry(newer.id, T0.plusSeconds(60)))
        val home = viewModel()
        watch(home.state)

        home.setHistorySort(HistorySort.Oldest)

        assertEquals(HistorySort.Oldest, fixture.settings.value.value.historySort)
        val tab = (home.state.value.history as Loadable.Ready).value
        assertEquals(HistorySort.Oldest to listOf(older.id, newer.id), tab.sort to tab.items.map { it.entry.book })
    }

    @Test
    fun `a book whose place lost its grant does not open and says why`() = runTest {
        val shelved = book(place.id, "a.zip")
        fixture.locations.rows.value = listOf(place.copy(permissionHeld = false))
        val home = viewModel()
        watch(home.state)

        assertNull(home.request(shelved))
        assertEquals(Notice.PlaceUnavailable, home.state.value.notice)
    }

    @Test
    fun `a book in a held place opens by its place and path`() = runTest {
        val shelved = book(place.id, "作品/1巻.zip")
        fixture.locations.rows.value = listOf(place)
        val home = viewModel()
        watch(home.state)

        assertEquals(HomeTarget.Book(OpenBookRequest(BookAddress.InLocation(place.id, "作品/1巻.zip", folder = false))), home.request(shelved))
        assertEquals(FolderKey(place.id, "作品"), home.folderOf(shelved))
    }

    @Test
    fun `a text from the history or the shelf opens in the text reader`() = runTest {
        val inPlace = book(place.id, "作品/後書き.txt").copy(format = BookFormat.Text(charset = null))
        val handed = inPlace.copy(source = BookSource.External(UriString("content://mail/後書き.txt")))
        fixture.locations.rows.value = listOf(place)
        val home = viewModel()
        watch(home.state)

        assertEquals(HomeTarget.Text(TextAddress.InLocation(place.id, "作品/後書き.txt")), home.request(inPlace))
        assertEquals(HomeTarget.Text(TextAddress.Document(UriString("content://mail/後書き.txt"))), home.request(handed))
    }

    @Test
    fun `the covers of the shelf and the history name the book, its chosen page and its direction`() = runTest {
        val first = book(place.id, "作品/1巻.zip")
        val text = book(place.id, "作品/後書き.txt").copy(format = BookFormat.Text(charset = null))
        val lost = book(LocationId(9), "x.zip")
        val handed = book(place.id, "y.zip").copy(source = BookSource.External(UriString("content://mail/y.zip")))
        fixture.locations.rows.value = listOf(place)
        fixture.books.put(first, text, lost, handed)
        fixture.books.chosenPages.value = mapOf(first.id to 5)
        fixture.books.put(reading(first, viewStart = 4))
        fixture.coverIndex.directions.value = mapOf(first.id to io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight)
        fixture.history.entries.value = listOf(first, text, lost, handed).mapIndexed { i, b -> HistoryEntry(b.id, T0.plusSeconds(i.toLong())) }
        val home = viewModel()
        watch(home.state)

        val shelf = (home.state.value.shelf as Loadable.Ready).value
        val spec = checkNotNull(shelf.continueReading.single().cover)
        assertEquals(5, spec.coverEntry)
        assertEquals(io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight, spec.direction)
        assertEquals(CoverOrigin.Archive(place, "作品/1巻.zip", null), spec.origin)
        assertEquals(386, shelf.covers.sizePx)

        val specs = (home.state.value.history as Loadable.Ready).value.specs
        assertEquals(setOf(first.id, handed.id), specs.keys)
        assertEquals(CoverOrigin.Document(UriString("content://mail/y.zip")), specs.getValue(handed.id).origin)
    }
}
