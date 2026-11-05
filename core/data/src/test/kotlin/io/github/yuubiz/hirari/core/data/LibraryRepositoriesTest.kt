package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.TextEntry
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.locationBookId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.charset.Charset
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoriesTest {
    private val database = inMemoryDatabase()
    private val credentialDatabase = inMemoryCredentialDatabase()
    private val credentials = testCredentials(credentialDatabase)
    private val locations = RoomLocationRepository(database, credentials)
    private val books = RoomBookRepository(database)
    private val progress = RoomProgressRepository(database.bookProgress())
    private val history = RoomHistoryRepository(database)
    private val bookmarks = RoomBookmarkRepository(database)
    private val covers = RoomCoverIndex(database)

    @After
    fun close() {
        database.close()
        credentialDatabase.close()
    }

    private fun at(seconds: Long) = Instant.ofEpochSecond(1_700_000_000 + seconds)

    private fun book(id: String, location: LocationId?, path: String, pageCount: Int? = null) = Book(
        id = BookId(id),
        source = if (location != null) BookSource.InLocation(location, path) else BookSource.External(UriString("content://other/$path")),
        format = BookFormat.Images(Container.Zip),
        title = path.substringAfterLast('/'),
        byteSize = 1000,
        modified = at(0),
        pageCount = pageCount,
    )

    private val nas = SmbServer(displayName = "NAS", host = "nas.local", share = "books", basePath = "manga", user = "reader")

    @Test
    fun `favorites round trip by place and normalized path independently of pins`() = runTest {
        val local = locations.addSafTree("漫画", UriString("content://tree/manga"))
        val remote = locations.addSmbShare(nas, null)
        locations.pin(local, "series", at(0))
        locations.addFavorite(local, "/series/", FavoriteKind.Folder, "series", at(1))
        locations.addFavorite(local, "series", FavoriteKind.Folder, "duplicate", at(9))
        locations.addFavorite(local, "series/1.cbz", FavoriteKind.Book, "1.cbz", at(2))
        locations.addFavorite(remote, "series", FavoriteKind.Folder, "remote series", at(3))

        val reopened = RoomLocationRepository(database, credentials)
        assertEquals(
            listOf(
                Favorite(remote, "series", FavoriteKind.Folder, "remote series", at(3)),
                Favorite(local, "series/1.cbz", FavoriteKind.Book, "1.cbz", at(2)),
                Favorite(local, "series", FavoriteKind.Folder, "series", at(1)),
            ),
            reopened.favorites.first(),
        )
        reopened.removeFavorite(local, "/series/")
        assertEquals(1, locations.pinned.first().size)
        assertEquals(2, locations.favorites.first().size)
        reopened.delete(local)
        assertEquals(listOf(Favorite(remote, "series", FavoriteKind.Folder, "remote series", at(3))), locations.favorites.first())
        assertEquals(emptyList(), locations.pinned.first())
    }

    @Test
    fun `a server keeps its password apart and an edit can keep, change or remove it`() = runTest {
        val id = locations.addSmbShare(nas, "first".toCharArray())
        val stored = assertIs<LibraryLocation.SmbShare>(locations.get(id))
        assertEquals("reader", stored.user)
        assertEquals("first", assertIs<StoredSecret.Found>(credentials.read(stored.credential!!)).secret.concatToString())

        locations.updateSmbShare(id, nas.copy(displayName = "Home"), PasswordChange.Keep)
        val kept = assertIs<LibraryLocation.SmbShare>(locations.get(id))
        assertEquals("Home", kept.displayName)
        assertEquals("first", assertIs<StoredSecret.Found>(credentials.read(kept.credential!!)).secret.concatToString())

        locations.updateSmbShare(id, nas, PasswordChange.Set("second".toCharArray()))
        val changed = assertIs<LibraryLocation.SmbShare>(locations.get(id))
        assertEquals("second", assertIs<StoredSecret.Found>(credentials.read(changed.credential!!)).secret.concatToString())

        locations.updateSmbShare(id, nas.copy(user = null), PasswordChange.Remove)
        val anonymous = assertIs<LibraryLocation.SmbShare>(locations.get(id))
        assertNull(anonymous.credential)
        assertEquals(StoredSecret.Missing, credentials.read(changed.credential!!))
    }

    @Test
    fun `every kind of image book reads back as the kind it was recorded as`() = runTest {
        Container.entries.forEach { container ->
            val book = book(container.name, location = null, path = "picked").copy(format = BookFormat.Images(container))
            books.record(book)
            assertEquals(book, books.get(book.id)?.book)
        }
    }

    @Test
    fun `changing the password of a server keeps what was read on it`() = runTest {
        val id = locations.addSmbShare(nas, "old".toCharArray())
        val before = locations.get(id)!!
        val bookId = locationBookId(before, "vol1.zip")
        progress.save(listOf(BookProgress(bookId, ReadState.Read, ReadingPosition.Image(99, 100), at(1))))

        locations.updateSmbShare(id, nas.copy(user = "another"), PasswordChange.Set("new".toCharArray()))
        val after = locations.get(id)!!
        assertEquals(bookId, locationBookId(after, "vol1.zip"))
        assertEquals(ReadState.Read, progress.get(bookId)?.state)
    }

    @Test
    fun `deleting a place removes its pins favorites covers and password but keeps progress`() = runTest {
        val id = locations.addSmbShare(nas, "secret".toCharArray())
        val ref = (locations.get(id) as LibraryLocation.SmbShare).credential!!
        locations.pin(id, "series/a", at(1))
        locations.addFavorite(id, "series/a", FavoriteKind.Folder, "a", at(1))
        locations.setFolderCover(id, "series/a", FolderCover("series/a/vol1.zip", entry = 3))
        progress.save(listOf(BookProgress(BookId("b"), ReadState.Reading, null, at(1))))

        assertEquals(nas.displayName, locations.delete(id)?.displayName)
        assertEquals(emptyList(), locations.locations.first())
        assertEquals(emptyList(), locations.pinned.first())
        assertEquals(emptyList(), locations.favorites.first())
        assertNull(locations.folderCover(id, "series/a").first())
        assertEquals(StoredSecret.Missing, credentials.read(ref))
        assertEquals(ReadState.Reading, progress.get(BookId("b"))?.state)
    }

    @Test
    fun `registering the same tree relinks history and bookmarks, while deleting the place drops its pins and favorites`() = runTest {
        val tree = UriString("content://tree/manga")
        val oldId = locations.addSafTree("Manga", tree)
        val original = book(locationBookId(locations.get(oldId)!!, "series/a.zip").value, oldId, "series/a.zip", pageCount = 100)
        books.record(original)
        books.setCover(original.id, 7)
        val saved = BookProgress(original.id, ReadState.Reading, ReadingPosition.Image(12, 100), at(1))
        progress.save(listOf(saved))
        history.record(original.id, at(2), 100)
        val mark = bookmarks.add(original.id, ReadingPosition.Image(6, 100), "chapter", at(3))
        locations.pin(oldId, "series", at(4))
        locations.addFavorite(oldId, "series/a.zip", FavoriteKind.Book, "a.zip", at(5))
        locations.addFavorite(oldId, "series", FavoriteKind.Folder, "series", at(6))
        assertEquals(1, locations.pinned.first().size)
        assertEquals(2, locations.favorites.first().size)

        locations.delete(oldId)
        assertNull(history.observe(HistorySort.Newest).first().single().location)
        assertEquals(emptyList(), locations.pinned.first())
        assertEquals(emptyList(), locations.favorites.first())
        val other = locations.addSafTree("Manga", UriString("content://tree/other"))
        assertEquals(BookSource.InLocation(oldId, "series/a.zip"), books.get(original.id)!!.book.source)
        assertNull(history.observe(HistorySort.Newest).first().single().location)

        // A new repository must recover from the persisted address, without in-memory state.
        val reopened = RoomLocationRepository(database, credentials)
        val newId = reopened.addSafTree("Renamed", tree)
        assertNotEquals(oldId, newId)
        val expected = original.copy(source = BookSource.InLocation(newId, "series/a.zip"))
        val item = history.observe(HistorySort.Newest).first().single()
        assertEquals(expected, item.book)
        assertEquals(newId, item.location!!.id)
        assertEquals(at(2), item.entry.openedAt)
        assertEquals(saved, item.progress)
        assertEquals(7, item.coverEntry)
        val group = bookmarks.observe(BookmarkSort.Newest).first().single()
        assertEquals(expected, group.info)
        assertEquals(newId, group.location!!.id)
        assertEquals(listOf(mark), group.bookmarks)
        assertEquals(emptyList(), locations.pinned.first())
        assertEquals(emptyList(), locations.favorites.first())
        assertEquals(listOf(other, newId), locations.locations.first().map { it.id })

        reopened.delete(newId)
        val thirdId = reopened.addSafTree("Again", tree)
        assertEquals(thirdId, history.observe(HistorySort.Newest).first().single().location!!.id)
        assertEquals(emptyList(), locations.pinned.first())
    }

    @Test
    fun `a server relinks only with the same host port share and normalized folder`() = runTest {
        val oldId = locations.addSmbShare(nas, "old-secret".toCharArray())
        val original = book(locationBookId(locations.get(oldId)!!, "a.zip").value, oldId, "a.zip")
        books.record(original)
        history.record(original.id, at(1), 100)
        bookmarks.add(original.id, ReadingPosition.Image(0, 100), null, at(2))
        locations.pin(oldId, "series", at(3))
        locations.addFavorite(oldId, "a.zip", FavoriteKind.Book, "a.zip", at(4))
        locations.delete(oldId)

        for (different in listOf(
            nas.copy(host = "other.local"), nas.copy(port = 1445), nas.copy(share = "other"), nas.copy(basePath = "Manga"),
        )) {
            locations.addSmbShare(different, null)
            assertNull(history.observe(HistorySort.Newest).first().single().location)
        }
        val same = nas.copy(host = "NAS.LOCAL", share = "BOOKS", basePath = "/./manga/", displayName = "New", user = "another", domain = "other")
        val newId = locations.addSmbShare(same, null)
        assertNotEquals(oldId, newId)
        assertEquals(original.id, locationBookId(locations.get(newId)!!, "a.zip"))
        assertEquals(BookSource.InLocation(newId, "a.zip"), history.observe(HistorySort.Newest).first().single().book!!.source)
        assertEquals(newId, history.observe(HistorySort.Newest).first().single().location!!.id)
        assertEquals(newId, bookmarks.observe(BookmarkSort.Newest).first().single().location!!.id)
        assertEquals(emptyList(), locations.pinned.first())
        assertEquals(emptyList(), locations.favorites.first())
    }

    @Test
    fun `adding the same address leaves a still registered places references alone`() = runTest {
        val tree = UriString("content://tree/manga")
        val alive = locations.addSafTree("Manga", tree)
        val removed = locations.addSafTree("Duplicate", tree)
        books.record(book("alive", alive, "a.zip"))
        books.record(book("removed", removed, "b.zip"))
        locations.pin(alive, "series", at(1))
        locations.pin(removed, "series", at(2))
        locations.addFavorite(alive, "a.zip", FavoriteKind.Book, "a.zip", at(1))
        locations.addFavorite(removed, "b.zip", FavoriteKind.Book, "b.zip", at(2))
        val keptPins = locations.pinned.first().filter { it.location == alive }
        val keptFavorites = locations.favorites.first().filter { it.location == alive }
        locations.delete(removed)

        val newId = locations.addSafTree("Again", tree)
        assertEquals(BookSource.InLocation(alive, "a.zip"), books.get(BookId("alive"))!!.book.source)
        assertEquals(BookSource.InLocation(newId, "b.zip"), books.get(BookId("removed"))!!.book.source)
        assertEquals(keptPins, locations.pinned.first())
        assertEquals(keptFavorites, locations.favorites.first())
    }

    @Test
    fun `several deleted copies of the same address relink their history and bookmarks`() = runTest {
        val tree = UriString("content://tree/manga")
        val first = locations.addSafTree("First", tree)
        val second = locations.addSafTree("Second", tree)
        val originals = listOf(book("first", first, "a.zip", 100), book("second", second, "b.zip", 100))
        val marks = originals.map { original ->
            books.record(original)
            progress.save(listOf(BookProgress(original.id, ReadState.Reading, ReadingPosition.Image(12, 100), at(1))))
            history.record(original.id, at(2), 100)
            bookmarks.add(original.id, ReadingPosition.Image(6, 100), "chapter", at(3))
        }
        val before = history.observe(HistorySort.Newest).first().associateBy { it.entry.book }
        for (id in listOf(first, second)) {
            locations.pin(id, "series", at(1))
            locations.addFavorite(id, "series", FavoriteKind.Folder, "series", at(2))
            locations.delete(id)
        }
        assertEquals(listOf(null, null), history.observe(HistorySort.Newest).first().map { it.location })
        val id = locations.addSafTree("Again", tree)
        val after = history.observe(HistorySort.Newest).first().associateBy { it.entry.book }
        val groups = bookmarks.observe(BookmarkSort.Newest).first().associateBy { it.info!!.id }
        assertEquals(before.keys, after.keys)
        assertEquals(before.keys, groups.keys)
        originals.forEachIndexed { index, original ->
            val expected = original.copy(source = BookSource.InLocation(id, (original.source as BookSource.InLocation).path))
            val item = after.getValue(original.id)
            assertEquals(expected, item.book)
            assertEquals(id, item.location!!.id)
            assertEquals(before.getValue(original.id).entry, item.entry)
            assertEquals(before.getValue(original.id).progress, item.progress)
            val group = groups.getValue(original.id)
            assertEquals(expected, group.info)
            assertEquals(id, group.location!!.id)
            assertEquals(listOf(marks[index]), group.bookmarks)
        }
        assertEquals(emptyList(), locations.pinned.first())
        assertEquals(emptyList(), locations.favorites.first())
    }

    @Test
    fun `a folder pinned twice is one pin, and a chosen cover reads back`() = runTest {
        val id = locations.addSafTree("Manga", UriString("content://tree/1"))
        locations.pin(id, "/series/a/", at(1))
        locations.pin(id, "series/a", at(2))
        assertEquals(listOf("series/a"), locations.pinned.first().map { it.path })
        locations.setFolderCover(id, "series/a", FolderCover("series/a/cover.jpg"))
        assertEquals(FolderCover("series/a/cover.jpg"), locations.folderCover(id, "series/a/").first())
        locations.setFolderCover(id, "series/a", null)
        assertNull(locations.folderCover(id, "series/a").first())
    }

    @Test
    fun `a restore marks the trees the app no longer holds a grant for`() = runTest {
        val held = locations.addSafTree("A", UriString("content://tree/a"))
        val lost = locations.addSafTree("B", UriString("content://tree/b"))
        locations.syncGrants(setOf(UriString("content://tree/a")))
        assertEquals(true, (locations.get(held) as LibraryLocation.SafTree).permissionHeld)
        assertEquals(false, (locations.get(lost) as LibraryLocation.SafTree).permissionHeld)
    }

    @Test
    fun `recording a book keeps its known page count and chosen cover`() = runTest {
        val place = locations.addSafTree("Manga", UriString("content://tree/1"))
        books.record(book("a", place, "series/a.zip", pageCount = 120))
        books.setCover(BookId("a"), 7)
        books.record(book("a", place, "series/a.zip", pageCount = null))
        val stored = books.get(BookId("a"))!!
        assertEquals(120, stored.book.pageCount)
        assertEquals(7, stored.coverEntry)
        assertEquals(BookSource.InLocation(place, "series/a.zip"), stored.book.source)
    }

    @Test
    fun `a range of the cover page is kept across opening the book and read back by the lists`() = runTest {
        val place = locations.addSafTree("Manga", UriString("content://tree/1"))
        books.record(book("a", place, "series/a.zip"))
        val crop = CoverCrop(1000, 500, 6000, 9500)
        books.setCover(BookId("a"), 7, crop)
        books.record(book("a", place, "series/a.zip"))
        assertEquals(crop, books.get(BookId("a"))!!.coverCrop)
        assertEquals(crop, books.inFolder(place, "series").first().single().coverCrop)
        history.record(BookId("a"), at(1), limit = 10)
        assertEquals(crop, history.observe(HistorySort.Newest).first().single().coverCrop)
    }

    @Test
    fun `choosing a page without a range, or no page, drops the range`() = runTest {
        val place = locations.addSafTree("Manga", UriString("content://tree/1"))
        books.record(book("a", place, "series/a.zip"))
        books.setCover(BookId("a"), 7, CoverCrop(1000, 500, 6000, 9500))
        books.setCover(BookId("a"), 8)
        assertEquals(8 to null, books.get(BookId("a"))!!.let { it.coverEntry to it.coverCrop })
        books.setCover(BookId("a"), 7, CoverCrop(1000, 500, 6000, 9500))
        books.setCover(BookId("a"), null, CoverCrop(1000, 500, 6000, 9500))
        assertEquals(null to null, books.get(BookId("a"))!!.let { it.coverEntry to it.coverCrop })
    }

    @Test
    fun `a folder lists only its own books with their progress, and follows changes`() = runTest {
        val place = locations.addSafTree("Manga", UriString("content://tree/1"))
        books.record(book("a", place, "series/a.zip"))
        books.record(book("b", place, "series/b.zip"))
        books.record(book("deeper", place, "series/more/c.zip"))
        books.record(book("top", place, "d.zip"))
        progress.save(listOf(BookProgress(BookId("a"), ReadState.Reading, ReadingPosition.Image(3, 10), at(1))))

        val folder = books.inFolder(place, "series").first().associateBy { it.book.id.value }
        assertEquals(setOf("a", "b"), folder.keys)
        assertEquals(ReadState.Reading, folder.getValue("a").progress?.state)
        assertNull(folder.getValue("b").progress)
        assertEquals(listOf("top"), books.inFolder(place, "").first().map { it.book.id.value })

        progress.mark(BookId("a"), ReadState.Read, at(2))
        assertEquals(ReadState.Read, books.inFolder(place, "series").first().first { it.book.id.value == "a" }.progress?.state)
    }

    @Test
    fun `marking a book unread forgets its position and marking it read keeps it`() = runTest {
        progress.save(listOf(BookProgress(BookId("a"), ReadState.Reading, ReadingPosition.Image(3, 10), at(1))))
        progress.mark(BookId("a"), ReadState.Read, at(2))
        assertEquals(ReadingPosition.Image(3, 10), progress.get(BookId("a"))?.position)
        progress.mark(BookId("a"), ReadState.Unread, at(3))
        assertEquals(BookProgress(BookId("a"), ReadState.Unread, null, at(3)), progress.get(BookId("a")))
    }

    @Test
    fun `continue reading lists books being read, last read first, up to the limit`() = runTest {
        listOf("a", "b", "c", "done").forEach { books.record(book(it, null, "$it.zip")) }
        progress.save(
            listOf(
                BookProgress(BookId("a"), ReadState.Reading, null, at(1)),
                BookProgress(BookId("b"), ReadState.Reading, null, at(3)),
                BookProgress(BookId("c"), ReadState.Reading, null, at(2)),
                BookProgress(BookId("done"), ReadState.Read, null, at(4)),
            ),
        )
        assertEquals(listOf("b", "c"), books.continueReading(limit = 2).first().map { it.book.id.value })
    }

    @Test
    fun `history keeps one entry per book and drops the oldest past the limit`() = runTest {
        history.record(BookId("a"), at(1), limit = 2)
        history.record(BookId("b"), at(2), limit = 2)
        history.record(BookId("a"), at(3), limit = 2)
        history.record(BookId("c"), at(4), limit = 2)
        assertEquals(listOf("c", "a"), history.observe(HistorySort.Newest).first().map { it.entry.book.value })
        assertEquals(listOf("a", "c"), history.observe(HistorySort.Oldest).first().map { it.entry.book.value })
        history.record(BookId("d"), at(5), limit = 0)
        assertEquals(emptyList(), history.observe(HistorySort.Newest).first())
    }

    @Test
    fun `two sessions of one book finishing in reverse order leave the newer time`() = runTest {
        history.record(BookId("other"), at(2), limit = 2)
        history.record(BookId("a"), at(5), limit = 2)
        history.record(BookId("a"), at(3), limit = 2)
        val entries = history.observe(HistorySort.Newest).first().map { it.entry }
        assertEquals(listOf("a" to at(5), "other" to at(2)), entries.map { it.book.value to it.openedAt })
    }

    @Test
    fun `recording the same time again changes nothing`() = runTest {
        history.record(BookId("a"), at(5), limit = 2)
        history.record(BookId("a"), at(5), limit = 2)
        assertEquals(listOf("a" to at(5)), history.observe(HistorySort.Newest).first().map { it.entry.book.value to it.entry.openedAt })
    }

    @Test
    fun `history sorts by path in natural order and shows the place`() = runTest {
        val place = locations.addSafTree("Manga", UriString("content://tree/1"))
        books.record(book("v10", place, "s/vol10.zip"))
        books.record(book("v2", place, "s/vol2.zip"))
        books.record(book("other", null, "x.zip"))
        history.record(BookId("v2"), at(1), 100)
        history.record(BookId("other"), at(2), 100)
        history.record(BookId("v10"), at(3), 100)
        history.record(BookId("gone"), at(4), 100)
        val ascending = history.observe(HistorySort.PathAscending).first()
        assertEquals(listOf("v2", "v10", "other", "gone"), ascending.map { it.entry.book.value })
        assertEquals("Manga", ascending.first().location?.displayName)
        assertNull(ascending.last().book)
        assertEquals(listOf("gone", "other", "v10", "v2"), history.observe(HistorySort.PathDescending).first().map { it.entry.book.value })
    }

    @Test
    fun `archive text bookmarks keep the archive address and entry when loaded from Room`() = runTest {
        val place = locations.addSafTree("Books", UriString("content://tree/books"))
        val sources = listOf(BookSource.InLocation(place, "novels.zip"), BookSource.External(UriString("content://other/novels.zip")))
        for ((index, source) in sources.withIndex()) {
            val text = book("entry$index", null, "novel.txt").copy(
                source = source, format = BookFormat.Text(null),
                textEntry = TextEntry(BookId("archive$index"), 3),
            )
            books.record(text)
            val position = ReadingPosition.Text(250, 1000)
            bookmarks.add(text.id, position, "chapter", at(1))
            val loaded = bookmarks.observe(BookmarkSort.Newest).first().single { it.book == text.id }
            assertEquals(text, loaded.info)
            assertEquals(position, loaded.bookmarks.single().position)
            assertEquals(text, books.get(text.id)!!.book)
        }
        // An entry's progress must not replace the state of its containing archive in the folder list.
        assertEquals(emptyList(), books.inFolder(place, "").first())
    }

    @Test
    fun `bookmarks group by book, take default labels and sort`() = runTest {
        val place = locations.addSafTree("Manga", UriString("content://tree/1"))
        books.record(book("b", place, "s/b.zip"))
        books.record(book("a", place, "s/a.zip"))
        bookmarks.add(BookId("b"), ReadingPosition.Image(11, 100), null, at(1))
        bookmarks.add(BookId("a"), ReadingPosition.Image(50, 100), "climax", at(2))
        bookmarks.add(BookId("a"), ReadingPosition.Image(5, 100), null, at(3))
        bookmarks.add(BookId("t"), ReadingPosition.Text(250, 1000), null, at(4))

        val byPath = bookmarks.observe(BookmarkSort.PathAscending).first()
        assertEquals(listOf("a", "b", "t"), byPath.map { it.book.value })
        assertEquals(listOf("p. 6", "climax"), byPath.first().bookmarks.map { it.label })
        assertEquals("p. 12", byPath[1].bookmarks.single().label)
        assertEquals("25%", byPath[2].bookmarks.single().label)

        val newest = bookmarks.observe(BookmarkSort.Newest).first()
        assertEquals(listOf("t", "a", "b"), newest.map { it.book.value })
        assertEquals(listOf("p. 6", "climax"), newest[1].bookmarks.map { it.label })
        assertEquals(listOf("b", "a", "t"), bookmarks.observe(BookmarkSort.Oldest).first().map { it.book.value })
        assertEquals(listOf("a", "b", "t"), bookmarks.observe(BookmarkSort.NameAscending).first().map { it.book.value })
        assertEquals(listOf("t", "b", "a"), bookmarks.observe(BookmarkSort.NameDescending).first().map { it.book.value })
    }

    @Test
    fun `bulk deletes reach only their scope`() = runTest {
        val tree = locations.addSafTree("Manga", UriString("content://tree/1"))
        val server = locations.addSmbShare(nas, null)
        books.record(book("inside", tree, "s/x/a.zip"))
        books.record(book("sibling", tree, "sx/b.zip"))
        books.record(book("remote", server, "s/x/c.zip"))
        books.record(book("external", null, "d.zip"))
        suspend fun remaining() = bookmarks.observe(BookmarkSort.PathAscending).first().map { it.book.value }.toSet()
        suspend fun reset() {
            bookmarks.delete(BookmarkScope.All)
            listOf("inside", "sibling", "remote", "external", "unknown").forEach {
                bookmarks.add(BookId(it), ReadingPosition.Image(0, 1), null, at(1))
            }
        }

        reset()
        bookmarks.delete(BookmarkScope.UnderFolder(tree, "s"))
        assertEquals(setOf("sibling", "remote", "external", "unknown"), remaining())

        reset()
        bookmarks.delete(BookmarkScope.InLocation(tree))
        assertEquals(setOf("remote", "external", "unknown"), remaining())

        reset()
        bookmarks.delete(BookmarkScope.OnDevice)
        assertEquals(setOf("remote"), remaining())

        reset()
        bookmarks.delete(BookmarkScope.Books(listOf(BookId("unknown"), BookId("external"))))
        assertEquals(setOf("inside", "sibling", "remote"), remaining())
    }

    @Test
    fun `book overrides read back and an empty set is removed`() = runTest {
        val overrides = BookOverrides(
            layoutMode = LayoutMode.Single,
            rotation = Rotation.Quarter,
            nameCharset = Charset.forName("EUC-JP"),
            adjust = ImageAdjust(level = 2, mono = true),
        )
        books.updateOverrides(BookId("a")) { overrides }
        assertEquals(overrides, books.overrides(BookId("a")).first())
        books.updateOverrides(BookId("a")) { BookOverrides() }
        assertEquals(BookOverrides(), books.overrides(BookId("a")).first())
        assertEquals(emptyList(), database.bookOverrides().all())
    }

    @Test
    fun `a change of one override keeps the others stored`() = runTest {
        val charset = Charset.forName("EUC-JP")
        books.updateOverrides(BookId("a")) { it.copy(rotation = Rotation.Quarter) }
        books.updateOverrides(BookId("a")) { it.copy(nameCharset = charset) }
        assertEquals(BookOverrides(rotation = Rotation.Quarter, nameCharset = charset), books.overrides(BookId("a")).first())
    }

    @Test
    fun `the cover index gives every set direction and every chosen folder cover of a place at once`() = runTest {
        books.updateOverrides(BookId("a")) { BookOverrides(direction = ReadingDirection.LeftToRight) }
        books.updateOverrides(BookId("b")) { BookOverrides(rotation = Rotation.Quarter) }
        books.updateOverrides(BookId("c")) { BookOverrides(direction = ReadingDirection.RightToLeft) }
        assertEquals(mapOf(BookId("a") to ReadingDirection.LeftToRight, BookId("c") to ReadingDirection.RightToLeft), covers.directions().first())
        books.updateOverrides(BookId("a")) { BookOverrides() }
        assertEquals(mapOf(BookId("c") to ReadingDirection.RightToLeft), covers.directions().first())

        val here = locations.addSafTree("Manga", UriString("content://tree/1"))
        val there = locations.addSafTree("Other", UriString("content://tree/2"))
        locations.setFolderCover(here, "series/a", FolderCover("series/a/cover.jpg"))
        locations.setFolderCover(here, "series/b", FolderCover("series/b/vol1.zip", entry = 3))
        locations.setFolderCover(there, "series/a", FolderCover("series/a/other.jpg"))
        assertEquals(
            mapOf("series/a" to FolderCover("series/a/cover.jpg"), "series/b" to FolderCover("series/b/vol1.zip", 3)),
            covers.folderCovers(here).first(),
        )
    }

    @Test
    fun `history carries the cover the user chose`() = runTest {
        val place = locations.addSafTree("Manga", UriString("content://tree/1"))
        books.record(book("a", place, "s/a.zip"))
        books.setCover(BookId("a"), 4)
        history.record(BookId("a"), at(1), 100)
        assertEquals(4, history.observe(HistorySort.Newest).first().single().coverEntry)
    }
}
