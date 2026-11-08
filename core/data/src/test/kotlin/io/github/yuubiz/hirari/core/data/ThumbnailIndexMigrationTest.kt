package io.github.yuubiz.hirari.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.model.BookId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class ThumbnailIndexMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** The thumbnail index as [version] of its schema made it, with [rows] inserted. */
    private fun createVersion(version: Int, vararg rows: String) {
        val file = File(context.noBackupFilesDir, "thumbs.db").also { it.parentFile!!.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/io.github.yuubiz.hirari.core.data.ThumbnailIndexDatabase/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schema.getValue("entities").jsonArray.forEach { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity.jsonObject.getValue("indices").jsonArray.forEach {
                    db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            rows.forEach(db::execSQL)
            db.version = version
        }
    }

    @Test fun `version one cache migrates without discarding unrelated entries and stores owners`() = runTest {
        createVersion(1, "INSERT INTO thumbs (digest, bytes, last_used, failed) VALUES ('legacy', 42, 10, 0)")
        val db = openThumbnailIndex(context)
        try {
            assertEquals(42L, db.thumbs().get("legacy")?.bytes)
            assertNull(db.thumbs().get("legacy")?.bookId)
            db.thumbs().upsert(ThumbRow("owned", 20, 11, false, "book"))
            assertEquals(listOf("owned"), db.thumbs().forBooks(listOf("book")).map { it.digest })
        } finally { db.close() }
        val reopened = openThumbnailIndex(context)
        try {
            assertEquals("book", reopened.thumbs().get("owned")?.bookId)
            assertEquals(42L, reopened.thumbs().get("legacy")?.bytes)
        } finally { reopened.close() }
    }

    @Test fun `version two cache keeps its thumbnails and gains the provisional covers`() = runTest {
        createVersion(2, "INSERT INTO thumbs (digest, bytes, last_used, failed, book_id) VALUES ('kept', 42, 10, 0, 'book')")
        val db = openThumbnailIndex(context)
        try {
            assertEquals("book", db.thumbs().get("kept")?.bookId)
            RoomProvisionalCovers(db.provisionalCovers()).remember(BookId("book"), 3)
        } finally { db.close() }
        val reopened = openThumbnailIndex(context)
        try {
            assertEquals(3, RoomProvisionalCovers(reopened.provisionalCovers()).entry(BookId("book")))
            assertEquals(42L, reopened.thumbs().get("kept")?.bytes)
        } finally { reopened.close() }
    }
}
