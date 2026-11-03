package io.github.yuubiz.hirari.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.UriString
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
