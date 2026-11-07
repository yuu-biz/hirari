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
import androidx.room3.withWriteTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ItemRecordsTest {
    private val db = inMemoryDatabase()
    private val records = RoomItemRecords(db)
    private val place = LibraryLocation.SafTree(LocationId(1), "本", UriString("content://books/tree/root"))
    private val rawName = byteArrayOf(0x82.toByte(), 0xa0.toByte(), 0x2e, 0x74, 0x78, 0x74)

    @After fun close() = db.close()

    private suspend fun add(path: String, text: Boolean = false, place: LibraryLocation = this.place): BookId {
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
        assertTrue(old in dropped.oldIds)
        assertTrue(locationBookId(place, "a") in dropped.oldIds)
        assertEquals(setOf(ChangedFolderCover(place, "a")), dropped.folderCovers)
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

    @Test fun `unreadable text stays under its old id while image records move`() = runTest {
        val book = add("a/b/book.cbz")
        val text = add("a/b/book.cbz", text = true)
        val result = records.rename(place, "a/b", "a/c") { _, _ -> error("unreadable archive") }
        assertEquals(1, result.skipped)
        assertRecords(book, false)
        assertRecords(text, true)
        assertRecords(locationBookId(place, "a/c/book.cbz"), true)
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
        val invalidated = records.delete(place, "a/b")
        assertTrue(locationBookId(place, "a") in invalidated.oldIds)
        assertEquals(setOf(ChangedFolderCover(place, "a")), invalidated.folderCovers)
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

    @Test fun `rename replaces all stale target records with the source records`() = runTest {
        val old = add("a/b/book.cbz")
        val text = add("a/b/book.cbz", text = true)
        val newArchive = add("a/c/book.cbz")
        val conflicting = add("a/c/book.cbz", text = true)
        db.folders().pin(PinnedFolderRow(locationId = 1, path = "a/b", pinnedAt = 5))
        db.folders().pin(PinnedFolderRow(locationId = 1, path = "a/c", pinnedAt = 9))
        db.folders().addFavorite(FavoriteRow(1, "a/b", FavoriteKind.Folder, "b", 5))
        db.folders().addFavorite(FavoriteRow(1, "a/c", FavoriteKind.Folder, "stale", 9))
        db.bookProgress().upsert(listOf(BookProgressRow(conflicting.value, ReadState.Read, null, 0, 0, 200)))
        records.rename(place, "a/b", "a/c") { _, _ -> rawName }
        assertRecords(old, false)
        assertRecords(text, false)
        assertRecords(newArchive, true)
        assertRecords(conflicting, true)
        assertEquals(ReadState.Reading, db.bookProgress().get(conflicting.value)?.state)
        assertEquals(1, db.bookmarks().all().count { it.bookId == conflicting.value })
        assertEquals(100L, db.history().get(conflicting.value)?.openedAt)
        assertEquals(5L, db.itemRecords().pins(1).single().pinnedAt)
        assertEquals(5L, db.itemRecords().favorites(1).single().addedAt)
    }

    @Test fun `a text row added during name collection stays under its old id`() = runTest {
        add("a/book.cbz", text = true)
        var late: BookId? = null
        val result = records.rename(place, "a", "b") { _, _ ->
            late = add("a/late.cbz", text = true)
            rawName
        }
        assertEquals(1, result.skipped)
        assertRecords(requireNotNull(late), true)
    }

    @Test fun `archive name collection permits an independent database writer`() = runTest {
        add("a/book.cbz", text = true)
        var wrote = false
        records.rename(place, "a", "b") { _, _ ->
            val writer = CoroutineScope(Dispatchers.IO).async { db.withWriteTransaction { wrote = true } }
            try { withTimeout(5_000) { writer.await() } } finally { writer.cancel() }
            rawName
        }
        assertTrue(wrote)
    }

    @Test fun `renaming an ancestor also moves a nested SMB place root`() = runTest {
        val root = LibraryLocation.SmbShare(LocationId(1), "root", "nas", 445, "books", "", null, null, null)
        val nested = root.copy(id = LocationId(2), basePath = "a/b")
        db.locations().insert(LocationRow(1, LocationKind.Smb, "root", host = "nas", port = 445, share = "books", basePath = ""))
        db.locations().insert(LocationRow(2, LocationKind.Smb, "nested", host = "nas", port = 445, share = "books", basePath = "a/b"))
        val old = add("book.cbz", place = nested)
        records.rename(root, "a", "renamed") { _, _ -> error("no text") }
        assertRecords(old, false)
        assertRecords(locationBookId(root, "renamed/b/book.cbz"), true)
        assertEquals("renamed/b", db.locations().get(2)?.basePath)
        assertEquals("book.cbz", db.books().get(locationBookId(root, "renamed/b/book.cbz").value)?.path)
    }

    @Test fun `SMB aliases move and delete by share root paths including per place choices`() = runTest {
        val root = LibraryLocation.SmbShare(LocationId(1), "root", "NAS", 445, "books", "", null, null, null)
        val nested = root.copy(id = LocationId(2), host = "nas", share = "BOOKS", basePath = "a")
        db.locations().insert(LocationRow(1, LocationKind.Smb, "root", host = "NAS", port = 445, share = "books", basePath = ""))
        db.locations().insert(LocationRow(2, LocationKind.Smb, "nested", host = "nas", port = 445, share = "BOOKS", basePath = "a"))
        val old = add("b/book.cbz", place = nested)
        val sibling = add("bc/book.cbz", place = nested)
        db.folders().addFavorite(FavoriteRow(1, "a/b", FavoriteKind.Folder, "b", 1))
        db.folders().addFavorite(FavoriteRow(2, "b", FavoriteKind.Folder, "b", 1))
        db.folders().pin(PinnedFolderRow(locationId = 2, path = "b", pinnedAt = 1))
        db.folders().setCover(FolderCoverRow(2, "", "b/book.cbz", null))
        val result = records.rename(root, "a/b", "a/c") { _, _ -> error("no text") }
        val new = locationBookId(root, "a/c/book.cbz")
        assertRecords(old, false)
        assertRecords(new, true)
        assertRecords(sibling, true)
        assertEquals("c/book.cbz", db.books().get(new.value)?.path)
        assertEquals("a/c", db.itemRecords().favorites(1).single().path)
        assertEquals("c", db.itemRecords().favorites(2).single().path)
        assertEquals("c", db.itemRecords().pins(2).single().path)
        assertTrue(locationBookId(nested, "") in result.oldIds)
        records.delete(root, "a/c")
        assertRecords(new, false)
        assertRecords(sibling, true)
        assertTrue(db.itemRecords().favorites(2).isEmpty())
        assertTrue(db.itemRecords().pins(2).isEmpty())
        assertTrue(db.itemRecords().covers(2).isEmpty())
    }

    @Test fun `SAF aliases share records only for the same tree URI`() = runTest {
        db.locations().insert(LocationRow(1, LocationKind.Saf, "one", treeUri = place.treeUri.value))
        db.locations().insert(LocationRow(2, LocationKind.Saf, "two", treeUri = place.treeUri.value))
        val other = place.copy(id = LocationId(3), treeUri = UriString("content://books/tree/other"))
        db.locations().insert(LocationRow(3, LocationKind.Saf, "other", treeUri = other.treeUri.value))
        val old = add("a/book.cbz", place = place.copy(id = LocationId(2)))
        val untouched = add("a/book.cbz", place = other)
        db.folders().addFavorite(FavoriteRow(2, "a/book.cbz", FavoriteKind.Book, "book.cbz", 1))
        records.rename(place, "a", "b") { _, _ -> error("no text") }
        assertRecords(old, false)
        assertRecords(locationBookId(place, "b/book.cbz"), true)
        assertRecords(untouched, true)
        assertEquals("b/book.cbz", db.itemRecords().favorites(2).single().path)
        records.delete(place, "b")
        assertRecords(locationBookId(place, "b/book.cbz"), false)
        assertRecords(untouched, true)
    }
}
