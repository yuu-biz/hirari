package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.TextEntry
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.entryBookId
import io.github.yuubiz.hirari.core.model.locationBookId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ItemRecordsTest {
    private val db = inMemoryDatabase()
    private val records = RoomItemRecords(db)
    private val place = LibraryLocation.SafTree(LocationId(1), "本", UriString("content://books/tree/root"))
    private val rawName = byteArrayOf(0x82.toByte(), 0xa0.toByte(), 0x2e, 0x74, 0x78, 0x74)

    @After fun close() = db.close()

    private suspend fun add(path: String, text: Boolean = false): BookId {
        val archive = locationBookId(place, path)
        val id = if (text) entryBookId(archive, rawName) else archive
        val book = Book(id, BookSource.InLocation(place.id, path), if (text) BookFormat.Text(null) else BookFormat.Images(Container.Zip),
            if (text) "本文.txt" else path.substringAfterLast('/'), 123, Instant.EPOCH, 10,
            textEntry = if (text) TextEntry(archive, 3) else null)
        db.books().record(book.toRow(null))
        db.bookProgress().upsert(listOf(BookProgressRow(id.value, ReadState.Reading, PositionKind.Image, 2, 10, 100)))
        db.history().upsert(listOf(HistoryRow(id.value, 100)))
        db.bookmarks().insert(BookmarkRow(bookId = id.value, positionKind = PositionKind.Image, positionAt = 2, positionTotal = 10, label = "目印", createdAt = 100))
        db.bookOverrides().upsert(listOf(BookOverridesRow(id.value, "{}")))
        return id
    }

    private suspend fun assertRecords(id: BookId, present: Boolean) {
        assertEquals(present, db.books().get(id.value) != null)
        assertEquals(present, db.bookProgress().get(id.value) != null)
        assertEquals(present, db.history().get(id.value) != null)
        assertEquals(present, db.bookmarks().all().any { it.bookId == id.value })
        assertEquals(present, db.bookOverrides().get(id.value) != null)
    }

    @Test fun `book rename moves all records and updates its title and chosen image paths`() = runTest {
        val old = add("a/book.cbz")
        db.folders().addFavorite(FavoriteRow(1, "a/book.cbz", FavoriteKind.Book, "book.cbz", 7))
        db.folders().setCover(FolderCoverRow(1, "a", "a/book.cbz", 4))
        val dropped = records.rename(place, "a/book.cbz", "a/new.cbz") { _, _ -> error("no text") }
        val new = locationBookId(place, "a/new.cbz")
        assertRecords(old, false)
        assertRecords(new, true)
        assertEquals("new.cbz", db.books().get(new.value)?.title)
        assertEquals(2, db.bookProgress().get(new.value)?.positionAt?.toInt())
        assertEquals("a/new.cbz", db.itemRecords().favorites(1).single().path)
        assertEquals("a/new.cbz", db.itemRecords().covers(1).single().imagePath)
        assertTrue(old in dropped)
    }

    @Test fun `folder rename includes indexed books and raw archive text names and leaves prefix siblings`() = runTest {
        val old = add("a/b/book.cbz")
        val text = add("a/b/book.cbz", text = true)
        val sibling = add("a/bc/book.cbz")
        db.folders().pin(PinnedFolderRow(locationId = 1, path = "a/b/child", pinnedAt = 5))
        db.folders().addFavorite(FavoriteRow(1, "a/b", FavoriteKind.Folder, "b", 8))
        db.folders().setCover(FolderCoverRow(1, "a/b", "a/b/cover.jpg", null))
        records.rename(place, "a/b", "a/renamed") { path, index ->
            assertEquals("a/renamed/book.cbz", path)
            assertEquals(3, index)
            rawName
        }
        val newArchive = locationBookId(place, "a/renamed/book.cbz")
        val newText = entryBookId(newArchive, rawName)
        assertRecords(old, false)
        assertRecords(text, false)
        assertRecords(newArchive, true)
        assertRecords(newText, true)
        assertRecords(sibling, true)
        assertEquals(newArchive.value, db.books().get(newText.value)?.textArchiveId)
        assertEquals("a/renamed/child", db.itemRecords().pins(1).single().path)
        assertEquals("renamed", db.itemRecords().favorites(1).single().displayName)
        assertEquals(FolderCoverRow(1, "a/renamed", "a/renamed/cover.jpg", null), db.itemRecords().covers(1).single())
    }

    @Test fun `failure to read a moved text name leaves every record at its original id`() = runTest {
        val book = add("a/b/book.cbz")
        val text = add("a/b/book.cbz", text = true)
        assertFailsWith<IllegalStateException> {
            records.rename(place, "a/b", "a/c") { _, _ -> error("unreadable archive") }
        }
        assertRecords(book, true)
        assertRecords(text, true)
        assertRecords(locationBookId(place, "a/c/book.cbz"), false)
    }

    @Test fun `delete removes descendants from every table and preserves a common prefix sibling`() = runTest {
        val book = add("a/b/book.cbz")
        val text = add("a/b/book.cbz", text = true)
        val sibling = add("a/bc/book.cbz")
        db.folders().pin(PinnedFolderRow(locationId = 1, path = "a/b", pinnedAt = 1))
        db.folders().pin(PinnedFolderRow(locationId = 1, path = "a/bc", pinnedAt = 2))
        db.folders().addFavorite(FavoriteRow(1, "a/b", FavoriteKind.Folder, "b", 1))
        db.folders().addFavorite(FavoriteRow(1, "a/bc", FavoriteKind.Folder, "bc", 2))
        db.folders().setCover(FolderCoverRow(1, "a", "a/b/cover.jpg", null))
        db.folders().setCover(FolderCoverRow(1, "a/bc", "a/bc/cover.jpg", null))
        records.delete(place, "a/b")
        assertRecords(book, false)
        assertRecords(text, false)
        assertRecords(sibling, true)
        assertEquals(listOf("a/bc"), db.itemRecords().pins(1).map { it.path })
        assertEquals(listOf("a/bc"), db.itemRecords().favorites(1).map { it.path })
        assertEquals(listOf("a/bc"), db.itemRecords().covers(1).map { it.path })
    }

    @Test fun `a marked book without an index is moved and deleted too`() = runTest {
        val old = locationBookId(place, "unopened.cbz")
        db.bookProgress().upsert(listOf(BookProgressRow(old.value, ReadState.Read, null, 0, 0, 5)))
        records.rename(place, "unopened.cbz", "renamed.cbz") { _, _ -> error("no text") }
        val new = locationBookId(place, "renamed.cbz")
        assertNull(db.bookProgress().get(old.value))
        assertEquals(ReadState.Read, db.bookProgress().get(new.value)?.state)
        records.delete(place, "renamed.cbz")
        assertNull(db.bookProgress().get(new.value))
    }

    @Test fun `a later id collision rolls back earlier record changes in the transaction`() = runTest {
        val old = add("a/b/book.cbz")
        val text = add("a/b/book.cbz", text = true)
        val newArchive = locationBookId(place, "a/c/book.cbz")
        val conflicting = entryBookId(newArchive, rawName)
        db.bookProgress().upsert(listOf(BookProgressRow(conflicting.value, ReadState.Read, null, 0, 0, 200)))
        assertFails { records.rename(place, "a/b", "a/c") { _, _ -> rawName } }
        assertRecords(old, true)
        assertRecords(text, true)
        assertRecords(newArchive, false)
        assertEquals(ReadState.Read, db.bookProgress().get(conflicting.value)?.state)
    }
}
