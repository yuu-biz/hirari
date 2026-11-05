package io.github.yuubiz.hirari.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.TextEntry
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.LocationId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Builds the file that version [version] of the app left behind, from the schema it exported. */
    private fun writeVersion(version: Int, extra: (SQLiteDatabase) -> Unit = {}) {
        val schema = Json.parseToJsonElement(File("schemas/io.github.yuubiz.hirari.core.data.HirariDatabase/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(MAIN_DATABASE).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schema.getValue("entities").jsonArray.forEach { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL(
                "INSERT INTO book_progress (book_id, state, position_kind, position_at, position_total, updated_at) " +
                    "VALUES ('kept', 'Reading', 'Image', 12, 200, 1700000000123)",
            )
            extra(db)
            db.version = version
        }
    }

    @Test
    fun `version 6 preserves records and deletion drops pins and favorites while reading records relink`() = runTest {
        writeVersion(6) { db ->
            db.execSQL("INSERT INTO locations (id, kind, display_name, tree_uri, permission_held, port) VALUES (1, 'Saf', '漫画', 'content://tree/manga', 1, 0)")
            db.execSQL("INSERT INTO books (book_id, location_id, path, folder, format, title, byte_size, modified, cover_entry) VALUES ('kept', 1, 'series/a.zip', 'series', 'Zip', 'a.zip', 1000, 0, 7)")
            db.execSQL("INSERT INTO history (book_id, opened_at) VALUES ('kept', 2000)")
            db.execSQL("INSERT INTO bookmarks (id, book_id, position_kind, position_at, position_total, label, created_at) VALUES (1, 'kept', 'Image', 6, 200, 'chapter', 3000)")
            db.execSQL("INSERT INTO pinned_folders (id, location_id, path, pinned_at) VALUES (1, 1, 'series', 4000)")
            db.execSQL("INSERT INTO favorites (location_id, path, kind, display_name, added_at) VALUES (1, 'series/a.zip', 'Book', 'a.zip', 5000)")
            // An already deleted version-6 place has no recoverable address. Never guess from its path.
            db.execSQL("INSERT INTO books (book_id, location_id, path, folder, format, title, byte_size, modified) VALUES ('unknown', 99, 'series/a.zip', 'series', 'Zip', 'a.zip', 1000, 0)")
        }
        val database = openDatabase(context)
        val credentialDatabase = inMemoryCredentialDatabase()
        try {
            val locations = RoomLocationRepository(database, testCredentials(credentialDatabase))
            val history = RoomHistoryRepository(database)
            val bookmarks = RoomBookmarkRepository(database)
            val oldId = LocationId(1)
            val before = history.observe(HistorySort.Newest).first().single()
            assertEquals(oldId, before.location!!.id)
            assertEquals(ReadingPosition.Image(12, 200), before.progress!!.position)
            val marks = bookmarks.inBook(BookId("kept")).first()
            assertEquals(listOf(PinnedFolder(1, oldId, "series")), locations.pinned.first())
            assertEquals(listOf(Favorite(oldId, "series/a.zip", FavoriteKind.Book, "a.zip", Instant.ofEpochMilli(5000))), locations.favorites.first())

            locations.delete(oldId)
            assertNull(history.observe(HistorySort.Newest).first().single().location)
            assertEquals(emptyList(), locations.pinned.first())
            assertEquals(emptyList(), locations.favorites.first())
            val id = locations.addSafTree("Again", UriString("content://tree/manga"))
            assertNotEquals(oldId, id)
            val after = history.observe(HistorySort.Newest).first().single()
            assertEquals(id, after.location!!.id)
            assertEquals(before.book!!.copy(source = BookSource.InLocation(id, "series/a.zip")), after.book)
            assertEquals(before.entry, after.entry)
            assertEquals(before.progress, after.progress)
            assertEquals(7, after.coverEntry)
            assertEquals(id, bookmarks.observe(BookmarkSort.Newest).first().single().location!!.id)
            assertEquals(marks, bookmarks.inBook(BookId("kept")).first())
            assertEquals(emptyList(), locations.pinned.first())
            assertEquals(emptyList(), locations.favorites.first())
            assertEquals(99L, database.books().get("unknown")!!.locationId)
        } finally {
            database.close()
            credentialDatabase.close()
        }
    }

    @Test
    fun `version 5 keeps pins and progress and adds an empty favorites table`() = runTest {
        writeVersion(5) { db ->
            db.execSQL("INSERT INTO locations (id, kind, display_name, tree_uri, permission_held, port) VALUES (1, 'Saf', '漫画', 'content://tree/manga', 1, 0)")
            db.execSQL("INSERT INTO pinned_folders (id, location_id, path, pinned_at) VALUES (1, 1, 'series', 1000)")
        }
        val database = openDatabase(context)
        val credentialDatabase = inMemoryCredentialDatabase()
        try {
            val locations = RoomLocationRepository(database, testCredentials(credentialDatabase))
            val place = io.github.yuubiz.hirari.core.model.LocationId(1)
            assertEquals(emptyList(), locations.favorites.first())
            assertEquals(listOf(PinnedFolder(1, place, "series")), locations.pinned.first())
            assertEquals(ReadingPosition.Image(12, 200), RoomProgressRepository(database.bookProgress()).get(BookId("kept"))!!.position)
            locations.addFavorite(place, "series/1.cbz", FavoriteKind.Book, "1.cbz", Instant.ofEpochMilli(2000))
            assertEquals(listOf(Favorite(place, "series/1.cbz", FavoriteKind.Book, "1.cbz", Instant.ofEpochMilli(2000))), locations.favorites.first())
        } finally {
            database.close()
            credentialDatabase.close()
        }
    }

    @Test
    fun `version 4 bookmarks survive and archive entry addresses can be saved after migration`() = runTest {
        writeVersion(4) { db ->
            db.execSQL("INSERT INTO books (book_id, uri, format, title, byte_size, modified) VALUES ('text', 'content://books/novel.txt', 'Text', 'novel.txt', 1000, 0)")
            db.execSQL("INSERT INTO bookmarks (id, book_id, position_kind, position_at, position_total, label, created_at) VALUES (1, 'text', 'Text', 250, 1000, 'chapter', 1)")
        }
        val database = openDatabase(context)
        try {
            val books = RoomBookRepository(database)
            val marks = RoomBookmarkRepository(database)
            val before = marks.observe(io.github.yuubiz.hirari.core.model.BookmarkSort.Newest).first().single()
            assertEquals(ReadingPosition.Text(250, 1000), before.bookmarks.single().position)
            assertEquals(null, before.info!!.textEntry)
            val entry = before.info!!.copy(textEntry = TextEntry(BookId("archive"), 3))
            books.record(entry)
            val after = marks.observe(io.github.yuubiz.hirari.core.model.BookmarkSort.Newest).first().single()
            assertEquals(entry, after.info)
            assertEquals(before.bookmarks, after.bookmarks)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a cover chosen in version 2 survives the upgrade as a whole page and takes a range`() = runTest {
        writeVersion(2) { db ->
            db.execSQL(
                "INSERT INTO books (book_id, location_id, path, folder, uri, format, charset, title, byte_size, modified, page_count, cover_entry) " +
                    "VALUES ('b', NULL, NULL, NULL, 'content://x/b.zip', 'Zip', NULL, 'b.zip', 100, 1700000000000, 20, 5)",
            )
        }
        val database = openDatabase(context)
        try {
            val books = RoomBookRepository(database)
            val before = books.get(BookId("b"))!!
            assertEquals(5, before.coverEntry)
            assertEquals(null, before.coverCrop)
            books.setCover(BookId("b"), 5, CoverCrop(0, 0, 5000, 5000))
            assertEquals(CoverCrop(0, 0, 5000, 5000), books.get(BookId("b"))!!.coverCrop)
        } finally {
            database.close()
        }
    }

    @Test
    fun `progress saved by version 1 survives the upgrade and the new tables work`() = runTest {
        writeVersion(1)
        val database = openDatabase(context)
        try {
            val progress = RoomProgressRepository(database.bookProgress())
            assertEquals(
                BookProgress(BookId("kept"), ReadState.Reading, ReadingPosition.Image(12, 200), Instant.ofEpochMilli(1_700_000_000_123)),
                progress.get(BookId("kept")),
            )
            val locations = RoomLocationRepository(database, testCredentials())
            val id = locations.addSafTree("Manga", UriString("content://tree/1"))
            assertEquals(listOf(id), locations.locations.first().map { it.id })
        } finally {
            database.close()
        }
    }
}
