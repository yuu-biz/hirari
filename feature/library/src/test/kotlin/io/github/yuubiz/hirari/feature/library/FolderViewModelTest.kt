package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.locationBookId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FolderViewModelTest {
    private val fixture = Fixture()
    private val place = LibraryLocation.SafTree(LocationId(1), "漫画", UriString("content://tree/manga"))

    private fun file(name: String) = FolderEntry(name, "doc:作品/$name", directory = false, size = 1, modified = null)

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun TestScope.viewModel(path: String = "作品", documentId: String? = "doc:作品") =
        FolderViewModel(CompletableDeferred(fixture.services), FolderKey(place.id, path, documentId), UnconfinedTestDispatcher(testScheduler))
            .also { watch(it.state) }

    private fun FolderViewModel.shown() = assertIs<FolderContent.Shown>(state.value.content)

    @Test
    fun `favorites toggle on books and folders and follow changes from other screens`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(file("1.cbz"), file("notes.txt"), file("p01.jpg"), FolderEntry("外伝", null, true, null, null))
        val model = viewModel()
        for (name in listOf("1.cbz", "notes.txt", "外伝")) {
            val row = model.shown().rows.first { it.item.entry.name == name }
            model.toggleFavorite(row)
            assertTrue(model.shown().rows.first { it.path == row.path }.favorite)
            val saved = fixture.locations.favorites.value.single { it.path == row.path }
            assertEquals(name, saved.displayName)
            assertEquals(if (name == "外伝") io.github.yuubiz.hirari.core.data.FavoriteKind.Folder else io.github.yuubiz.hirari.core.data.FavoriteKind.Book, saved.kind)
            model.toggleFavorite(row)
            assertTrue(model.shown().rows.none { it.path == row.path && it.favorite })
        }
        model.toggleFavorite()
        assertTrue(model.state.value.favorite)
        assertEquals("作品", fixture.locations.favorites.value.single().path)
        assertTrue(fixture.locations.pins.value.isEmpty())
        fixture.locations.removeFavorite(place.id, "作品")
        assertTrue(!model.state.value.favorite)
        model.toggleFavorite(model.shown().rows.first { it.item is FileItem.Image })
        assertTrue(fixture.locations.favorites.value.isEmpty())
    }

    @Test
    fun `the top of a place cannot be favorited from its top bar`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders[""] = emptyList()
        val model = viewModel("")
        model.toggleFavorite()
        assertTrue(fixture.locations.favorites.value.isEmpty())
    }

    @Test
    fun `the folder sheet and an image tap address the same book with different starting positions`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(FolderEntry("外伝", null, true, null, null))
        fixture.lister.folders["作品/外伝"] = listOf(file("p01.jpg"))
        val parent = viewModel()
        val request = parent.folderBook(parent.shown().rows.single())!!
        val child = viewModel("作品/外伝", documentId = null)
        val tapped = assertIs<FileTarget.Book>(child.open(child.shown().rows.single())).request
        assertEquals(tapped.book, request.book)
        assertEquals(StartAt.Saved, request.start)
        assertEquals(StartAt.Image("p01.jpg"), tapped.start)
    }

    @Test
    fun `an SMB folder book uses its folder path and saved position after one child listing`() = runTest {
        val server = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "", null, null, null)
        fixture.locations.rows.value = listOf(server)
        fixture.smb.folders["作品"] = listOf(FolderEntry("外伝", null, true, null, null))
        fixture.smb.folders["作品/外伝"] = listOf(FolderEntry("p01.jpg", null, false, 1, null))
        val parent = FolderViewModel(CompletableDeferred(fixture.services), FolderKey(server.id, "作品"), UnconfinedTestDispatcher(testScheduler))
            .also { watch(it.state) }
        assertEquals(
            OpenBookRequest(BookAddress.InLocation(server.id, "作品/外伝", folder = true), StartAt.Saved),
            parent.folderBook(parent.shown().rows.single()),
        )
        assertEquals(listOf(server to "作品", server to "作品/外伝"), fixture.smb.listed)
        assertEquals(0, fixture.lister.calls)
    }

    @Test
    fun `a folder that cannot be listed offers no folder book`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(FolderEntry("外伝", "doc:外伝", true, null, null))
        val parent = viewModel()
        assertEquals(null, parent.folderBook(parent.shown().rows.single()))
        assertEquals(2, fixture.lister.calls)
    }

    @Test
    fun `a book read in the reader changes its mark on the list without listing again`() = runTest {
        val first = book(place.id, "作品/1巻.zip").copy(id = locationBookId(place, "作品/1巻.zip"))
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(file("1巻.zip"), file("2巻.zip"))
        val list = viewModel()
        assertEquals(listOf(ReadMark.Unread, ReadMark.Unread), list.shown().rows.map { it.mark })

        fixture.books.put(first)
        fixture.books.put(reading(first, viewStart = 4))
        assertEquals(listOf(ReadMark.Reading(50), ReadMark.Unread), list.shown().rows.map { it.mark })

        fixture.books.put(reading(first, viewStart = 9, state = ReadState.Read))
        assertEquals(listOf(ReadMark.Read, ReadMark.Unread), list.shown().rows.map { it.mark })
        assertEquals(1, fixture.lister.calls)
    }

    @Test
    fun `changing the order and the filter rebuilds the rows from the one listing`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(file("a.zip"), file("b.zip"), file("ab.zip"))
        val list = viewModel()

        list.setFilter("B")
        assertEquals(listOf("ab.zip", "b.zip"), list.shown().rows.map { it.item.entry.name })
        assertEquals(3, list.shown().total)

        list.setSort(LibrarySort.Oldest)
        assertEquals(LibrarySort.Oldest, fixture.settings.value.value.librarySort)
        assertEquals(LibrarySort.Oldest, list.shown().sort)
        assertEquals(1, fixture.lister.calls)
    }

    @Test
    fun `a slow listing shows progress after 300 ms and then its rows`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(file("a.zip"))
        fixture.lister.delayMs = 1_000
        val list = viewModel()

        advanceTimeBy(SLOW_LISTING_MS - 1)
        assertEquals(FolderContent.Loading, list.state.value.content)
        advanceTimeBy(2)
        assertEquals(FolderContent.SlowLoading, list.state.value.content)
        advanceUntilIdle()
        assertEquals(1, list.shown().rows.size)
    }

    @Test
    fun `a place without its grant fails without asking the provider`() = runTest {
        fixture.locations.rows.value = listOf(place.copy(permissionHeld = false))
        val list = viewModel()

        assertEquals(FolderContent.Failed(ListFailure.NoPermission), list.state.value.content)
        assertEquals(0, fixture.lister.calls)
    }

    @Test
    fun `taps open folders, books, images and texts by their place and path`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(FolderEntry("外伝", "doc:外伝", directory = true, size = null, modified = null), file("1巻.zip"), file("p01.jpg"), file("後書き.txt"))
        val list = viewModel(documentId = null)
        list.setFilter("1")
        val rows = list.shown().rows
        val all = (fixture.lister.folders["作品"]!!).toRows("作品", Ordering(LibrarySort.Name, foldersFirst = true, showDotFiles = false))

        assertEquals(FileTarget.Folder(FolderKey(place.id, "作品/外伝", "doc:外伝")), list.open(all[0]))
        assertEquals("", list.shown().filter)
        assertEquals(
            FileTarget.Book(OpenBookRequest(BookAddress.InLocation(place.id, "作品/1巻.zip", folder = false, "doc:作品/1巻.zip"))),
            list.open(rows.first { it.item is FileItem.Archive }),
        )
        assertEquals(
            FileTarget.Book(OpenBookRequest(BookAddress.InLocation(place.id, "作品", folder = true, "doc:作品"), StartAt.Image("p01.jpg"))),
            list.open(all[2]),
        )
        assertEquals(
            FileTarget.Text(TextAddress.InLocation(place.id, "作品/後書き.txt", "doc:作品/後書き.txt")),
            list.open(all.first { it.item is FileItem.Text }),
        )
    }

    @Test
    fun `an SMB place lists through its server, marks its books by place and path, and opens its folders, books and texts`() = runTest {
        val server = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "漫画", null, "reader", null)
        fixture.locations.rows.value = listOf(server)
        fixture.smb.folders["作品"] = listOf(
            FolderEntry("外伝", null, directory = true, size = null, modified = null),
            FolderEntry("1巻.zip", null, directory = false, size = 1, modified = null),
            FolderEntry("p01.jpg", null, directory = false, size = 1, modified = null),
            FolderEntry("後書き.txt", null, directory = false, size = 1, modified = null),
        )
        val list = FolderViewModel(CompletableDeferred(fixture.services), FolderKey(server.id, "作品"), UnconfinedTestDispatcher(testScheduler))
            .also { watch(it.state) }
        val first = book(server.id, "作品/1巻.zip").copy(id = locationBookId(server, "作品/1巻.zip"))
        fixture.books.put(reading(first, viewStart = 4))

        assertEquals(listOf(server to "作品"), fixture.smb.listed)
        assertEquals("NAS", list.state.value.placeName)
        val rows = list.shown().rows.associateBy { it.path }
        assertEquals(ReadMark.Reading(50), rows.getValue("作品/1巻.zip").mark)
        assertEquals(FileTarget.Folder(FolderKey(server.id, "作品/外伝")), list.open(rows.getValue("作品/外伝")))
        assertEquals(
            FileTarget.Book(OpenBookRequest(BookAddress.InLocation(server.id, "作品/1巻.zip", folder = false))),
            list.open(rows.getValue("作品/1巻.zip")),
        )
        assertEquals(
            FileTarget.Book(OpenBookRequest(BookAddress.InLocation(server.id, "作品", folder = true), StartAt.Image("p01.jpg"))),
            list.open(rows.getValue("作品/p01.jpg")),
        )
        assertEquals(FileTarget.Text(TextAddress.InLocation(server.id, "作品/後書き.txt")), list.open(rows.getValue("作品/後書き.txt")))
        assertEquals(null, list.state.value.notice)
        assertEquals(0, fixture.lister.calls)
    }

    @Test
    fun `an SMB item is saved to the picked folder, which is remembered and keeps its write grant until another is picked`() = runTest {
        val server = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "漫画", null, null, null)
        fixture.locations.rows.value = listOf(server)
        fixture.smb.folders["作品"] = listOf(
            FolderEntry("外伝", null, directory = true, size = null, modified = null),
            FolderEntry("1巻.zip", null, directory = false, size = 1, modified = null),
        )
        fixture.smb.saving = { awaitCancellation() }
        val list = FolderViewModel(CompletableDeferred(fixture.services), FolderKey(server.id, "作品"), UnconfinedTestDispatcher(testScheduler))
            .also { watch(it.state) }
            .also { watch(it.activeSaves) }
        val rows = list.shown().rows.associateBy { it.path }
        assertTrue(list.state.value.canSave)
        assertEquals(null, list.lastSaveFolder())

        val first = UriString("content://tree/saves")
        list.save(rows.getValue("作品/1巻.zip"), first)
        assertEquals(first, list.lastSaveFolder())
        assertEquals(setOf(first), fixture.grants.writeHeld)
        assertEquals(Notice.SaveStarted, list.state.value.notice)
        val book = fixture.smb.saved.single()
        assertEquals(Triple("作品/1巻.zip", false, first), Triple(book.path, book.directory, fixture.saveFolders.opened.single()))
        assertEquals(listOf("1巻.zip"), list.activeSaves.value.map { it.name })

        val second = UriString("content://tree/elsewhere")
        list.save(rows.getValue("作品/外伝"), second)
        assertEquals(second, list.lastSaveFolder())
        assertTrue(fixture.smb.saved.last().directory)
        assertEquals(emptyList(), fixture.grants.writeReleased)
        assertEquals(setOf(first, second), fixture.grants.writeHeld)

        fixture.saves.cancelAll()
        val third = UriString("content://tree/third")
        list.save(rows.getValue("作品/1巻.zip"), third)
        assertEquals(listOf(second), fixture.grants.writeReleased)
        fixture.saves.cancelAll()
    }

    @Test
    fun `a SAF place offers no save and saves nothing`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(file("1巻.zip"))
        val list = viewModel()
        assertEquals(false, list.state.value.canSave)
        list.save(list.shown().rows.single(), UriString("content://tree/saves"))
        assertTrue(fixture.smb.saved.isEmpty())
        assertEquals(null, list.lastSaveFolder())
    }

    @Test
    fun `an SMB failure shows its reason and a retry lists again`() = runTest {
        val server = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "", null, null, null)
        fixture.locations.rows.value = listOf(server)
        fixture.smb.failure = ListFailure.NoLocalNetwork
        val list = FolderViewModel(CompletableDeferred(fixture.services), FolderKey(server.id, ""), UnconfinedTestDispatcher(testScheduler))
            .also { watch(it.state) }
        assertEquals(FolderContent.Failed(ListFailure.NoLocalNetwork), list.state.value.content)

        fixture.smb.failure = null
        fixture.smb.folders[""] = emptyList()
        list.load()
        assertEquals(0, list.shown().total)
    }

    @Test
    fun `a retry after the reconnects gave up reconnects the server before it lists again`() = runTest {
        val server = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "", null, null, null)
        fixture.locations.rows.value = listOf(server)
        fixture.smb.folders[""] = emptyList()
        fixture.smb.lost = true
        val list = FolderViewModel(CompletableDeferred(fixture.services), FolderKey(server.id, ""), UnconfinedTestDispatcher(testScheduler))
            .also { watch(it.state) }
        assertEquals(FolderContent.Failed(ListFailure.Unreachable), list.state.value.content)

        list.load()
        assertEquals(listOf(server.id), fixture.smb.reconnects)
        assertEquals(0, list.shown().total)
    }

    @Test
    fun `marking a book never opened writes its progress and changes its mark`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(file("1巻.zip"), file("2巻.zip"))
        val list = viewModel()
        val row = list.shown().rows.first()

        list.mark(row, ReadState.Read)
        assertEquals(ReadState.Read, fixture.books.progress.value[locationBookId(place, "作品/1巻.zip")]?.state)
        assertEquals(listOf(ReadMark.Read, ReadMark.Unread), list.shown().rows.map { it.mark })

        list.mark(list.shown().rows.first(), ReadState.Unread)
        assertEquals(listOf(ReadMark.Unread, ReadMark.Unread), list.shown().rows.map { it.mark })
        assertEquals(1, fixture.lister.calls)
    }

    @Test
    fun `reading the folder again keeps the rows and the filter shown until the new listing swaps them in`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(file("1巻.zip"), file("2巻.zip"))
        val list = viewModel()
        list.setFilter("巻")
        fixture.lister.folders["作品"] = listOf(file("1巻.zip"), file("2巻.zip"), file("3巻.zip"), file("外伝.zip"))
        fixture.lister.delayMs = 1_000

        list.refresh()
        advanceTimeBy(SLOW_LISTING_MS + 1)
        assertTrue(list.state.value.refreshing)
        assertEquals(listOf("1巻.zip", "2巻.zip"), list.shown().rows.map { it.item.entry.name })

        advanceUntilIdle()
        assertEquals(false, list.state.value.refreshing)
        assertEquals(listOf("1巻.zip", "2巻.zip", "3巻.zip"), list.shown().rows.map { it.item.entry.name })
        assertEquals("巻", list.shown().filter)
        assertEquals(2, fixture.lister.calls)
    }

    @Test
    fun `reading again a folder that failed lists it as at first`() = runTest {
        fixture.locations.rows.value = listOf(place)
        val list = viewModel()
        assertIs<FolderContent.Failed>(list.state.value.content)
        fixture.lister.folders["作品"] = listOf(file("1巻.zip"))
        fixture.lister.delayMs = 1_000

        list.refresh()
        assertEquals(FolderContent.Loading, list.state.value.content)
        advanceUntilIdle()
        assertEquals(listOf("1巻.zip"), list.shown().rows.map { it.item.entry.name })
    }
}
