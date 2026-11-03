package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.Rotation
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
    fun `deleting a place removes its pins, covers and password but keeps progress`() = runTest {
        val id = locations.addSmbShare(nas, "secret".toCharArray())
        val ref = (locations.get(id) as LibraryLocation.SmbShare).credential!!
        locations.pin(id, "series/a", at(1))
        locations.setFolderCover(id, "series/a", FolderCover("series/a/vol1.zip", entry = 3))
        progress.save(listOf(BookProgress(BookId("b"), ReadState.Reading, null, at(1))))

        assertEquals(nas.displayName, locations.delete(id)?.displayName)
        assertEquals(emptyList(), locations.locations.first())
        assertEquals(emptyList(), locations.pinned.first())
        assertNull(locations.folderCover(id, "series/a").first())
        assertEquals(StoredSecret.Missing, credentials.read(ref))
        assertEquals(ReadState.Reading, progress.get(BookId("b"))?.state)
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
        books.setOverrides(BookId("a"), overrides)
        assertEquals(overrides, books.overrides(BookId("a")).first())
        books.setOverrides(BookId("a"), BookOverrides())
        assertEquals(BookOverrides(), books.overrides(BookId("a")).first())
        assertEquals(emptyList(), database.bookOverrides().all())
    }

    @Test
    fun `the cover index gives every set direction and every chosen folder cover of a place at once`() = runTest {
        books.setOverrides(BookId("a"), BookOverrides(direction = ReadingDirection.LeftToRight))
        books.setOverrides(BookId("b"), BookOverrides(rotation = Rotation.Quarter))
        books.setOverrides(BookId("c"), BookOverrides(direction = ReadingDirection.RightToLeft))
        assertEquals(mapOf(BookId("a") to ReadingDirection.LeftToRight, BookId("c") to ReadingDirection.RightToLeft), covers.directions().first())
        books.setOverrides(BookId("a"), BookOverrides())
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
