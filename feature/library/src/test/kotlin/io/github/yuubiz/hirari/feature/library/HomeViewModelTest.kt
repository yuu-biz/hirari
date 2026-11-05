package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.BookmarkGroup
import io.github.yuubiz.hirari.core.data.FavoriteKind
import io.github.yuubiz.hirari.core.data.Favorite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
import kotlinx.coroutines.test.runCurrent
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

    private fun viewModel() = HomeViewModel(CompletableDeferred(fixture.services), UnconfinedTestDispatcher())

    @Test
    fun `favorites list newest first and open folders or unread books by their lasting address`() = runTest {
        val server = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas", 445, "books", "", null, null, null)
        fixture.locations.rows.value = listOf(place, server)
        fixture.locations.addFavorite(place.id, "series", FavoriteKind.Folder, "series", T0)
        fixture.locations.addFavorite(place.id, "series/1.cbz", FavoriteKind.Book, "1.cbz", T0.plusSeconds(1))
        fixture.locations.addFavorite(server.id, "novel.txt", FavoriteKind.Book, "novel.txt", T0.plusSeconds(2))
        val home = viewModel()
        watch(home.state)
        val tab = assertIs<Loadable.Ready<FavoritesTab>>(home.state.value.favorites).value
        assertEquals(listOf("novel.txt", "1.cbz", "series"), tab.items.map { it.favorite.displayName })
        assertEquals(listOf("NAS", "漫画", "漫画"), tab.items.map { it.place?.displayName })
        assertEquals(HomeTarget.Folder(FolderKey(place.id, "series")), home.request(tab.items[2]))
        assertEquals(HomeTarget.Book(OpenBookRequest(BookAddress.InLocation(place.id, "series/1.cbz", folder = false))), home.request(tab.items[1]))
        assertEquals(HomeTarget.Text(TextAddress.InLocation(server.id, "novel.txt")), home.request(tab.items[0]))
        assertTrue(fixture.coverBooks.listCalls.isEmpty(), "offscreen favorites must not ask the storage for cover metadata")
        home.removeFavorite(tab.items[1])
        assertEquals(listOf("novel.txt", "series"), assertIs<Loadable.Ready<FavoritesTab>>(home.state.value.favorites).value.items.map { it.favorite.displayName })
    }

    @Test
    fun `unavailable favorites explain the reason on tap and remain listed until explicitly removed`() = runTest {
        fixture.locations.rows.value = listOf(place.copy(permissionHeld = false))
        fixture.locations.favorites.value = listOf(
            Favorite(place.id, "series", FavoriteKind.Folder, "series", T0),
            Favorite(place.id, "1.cbz", FavoriteKind.Book, "1.cbz", T0),
            Favorite(LocationId(99), "lost", FavoriteKind.Folder, "lost", T0),
            Favorite(LocationId(99), "lost.cbz", FavoriteKind.Book, "lost.cbz", T0),
        )
        val home = viewModel()
        watch(home.state)
        val tab = assertIs<Loadable.Ready<FavoritesTab>>(home.state.value.favorites).value
        for (item in tab.items) {
            home.dismissNotice()
            assertNull(home.request(item))
            assertEquals(Notice.PlaceUnavailable, home.state.value.notice)
            assertTrue(home.favoriteCovers(listOf(item)).first().isEmpty())
        }
        assertEquals(4, assertIs<Loadable.Ready<FavoritesTab>>(home.state.value.favorites).value.items.size)
        fixture.locations.rows.value = listOf(place)
        val restored = assertIs<Loadable.Ready<FavoritesTab>>(home.state.value.favorites).value.items.first()
        assertEquals(HomeTarget.Folder(FolderKey(place.id, "series")), home.request(restored))
    }

    @Test
    fun `visible favorites use the file list cover keys including folder choices and book crops`() = runTest {
        fixture.locations.rows.value = listOf(place)
        val entries = listOf(
            FolderEntry("1.cbz", "doc:1", false, 500, T0),
            FolderEntry("extra", "doc:extra", true, null, T0),
        )
        fixture.coverBooks.folders["series"] = entries
        val indexed = book(place.id, "series/1.cbz").copy(id = io.github.yuubiz.hirari.core.model.locationBookId(place, "series/1.cbz"), byteSize = 500)
        fixture.books.put(indexed)
        fixture.books.chosenPages.value = mapOf(indexed.id to 3)
        fixture.books.chosenCrops.value = mapOf(indexed.id to io.github.yuubiz.hirari.core.model.CoverCrop(0, 0, 5000, 5000))
        fixture.coverIndex.folderCovers.value = mapOf("series/extra" to io.github.yuubiz.hirari.core.data.FolderCover("series/extra/cover.jpg"))
        fixture.locations.addFavorite(place.id, "series/1.cbz", FavoriteKind.Book, "1.cbz", T0)
        fixture.locations.addFavorite(place.id, "series/extra", FavoriteKind.Folder, "extra", T0)
        val home = viewModel()
        watch(home.state)
        val tab = assertIs<Loadable.Ready<FavoritesTab>>(home.state.value.favorites).value
        val rows = entries.toRows("series", Ordering(io.github.yuubiz.hirari.core.model.LibrarySort.Name, false, false)).withCovers(
            fixture.books.inFolder(place.id, "series").first(),
            CoverContext(place, fixture.settings.value.value.readingDirection, emptyMap(), fixture.coverIndex.folderCovers.value),
        )
        val specs = home.favoriteCovers(tab.items).first()
        val folder = tab.items.single { it.favorite.kind == FavoriteKind.Folder }
        assertEquals(rows.single { it.path == folder.favorite.path }.cover, specs[folder.favorite])
        val favorite = tab.items.single { it.favorite.kind == FavoriteKind.Book }.favorite
        assertEquals(coverSpecOf(indexed, 3, place, fixture.settings.value.value.readingDirection, fixture.books.chosenCrops.value[indexed.id]), specs[favorite])
        assertEquals(rows.single { it.path == favorite.path }.cover!!.key(tab.covers.sizePx), specs.getValue(favorite).key(tab.covers.sizePx))
    }

    @Test
    fun `favorites share one retained parent lookup and observation and indexed books need no listing`() = runTest {
        val names = listOf("known.cbz", "one.cbz", "two.cbz", "extra")
        fixture.coverBooks.folders["series"] = names.map { FolderEntry(it, null, it == "extra", 500, T0) }
        val known = book(place.id, "series/known.cbz").copy(id = io.github.yuubiz.hirari.core.model.locationBookId(place, "series/known.cbz"))
        fixture.books.put(known)
        fun item(name: String) = FavoriteItem(Favorite(place.id, "series/$name", if (name == "extra") FavoriteKind.Folder else FavoriteKind.Book, name, T0), place)
        val home = viewModel()
        val knownItem = item("known.cbz")
        assertEquals(coverSpecOf(known, null, place, fixture.settings.value.value.readingDirection), home.favoriteCovers(listOf(knownItem)).first()[knownItem.favorite])
        assertTrue(fixture.coverBooks.listCalls.isEmpty())
        fixture.books.folderObservations.clear()
        fixture.books.folderStops.clear()
        val items = names.map(::item)
        val emissions = mutableListOf<Map<Favorite, CoverSpec>>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { home.favoriteCovers(items).toList(emissions) }
        runCurrent()
        assertEquals(4, emissions.last().size)
        assertEquals(listOf("series"), fixture.coverBooks.listCalls)
        assertEquals(listOf(place.id to "series"), fixture.books.folderObservations)
        // Cover changes keep the same folder work and update all items from that observation.
        fixture.books.setCover(known.id, 4)
        fixture.coverIndex.directions.value = mapOf(known.id to io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight)
        fixture.coverIndex.folderCovers.value = mapOf("series/extra" to io.github.yuubiz.hirari.core.data.FolderCover("series/extra/new.jpg"))
        runCurrent()
        val updated = assertIs<CoverSpec.Book>(emissions.last()[knownItem.favorite])
        assertEquals(4, updated.coverEntry)
        assertEquals(io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight, updated.direction)
        assertEquals("series/extra/new.jpg", assertIs<CoverSpec.Folder>(emissions.last()[item("extra").favorite]).selection?.imagePath)
        assertEquals(listOf("series"), fixture.coverBooks.listCalls)
        assertEquals(1, fixture.books.folderObservations.size)
        assertTrue(fixture.books.folderStops.isEmpty())
        collection.cancel()
        runCurrent()
        assertEquals(listOf(place.id to "series"), fixture.books.folderStops)
    }

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
