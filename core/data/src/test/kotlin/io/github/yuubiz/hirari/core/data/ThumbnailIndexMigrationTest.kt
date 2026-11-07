package io.github.yuubiz.hirari.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
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
    @Test fun `version one cache migrates without discarding unrelated entries and stores owners`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "thumbs.db").also { it.parentFile!!.mkdirs() }
        val schema = Json.parseToJsonElement(File("schemas/io.github.yuubiz.hirari.core.data.ThumbnailIndexDatabase/1.json").readText())
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
            db.execSQL("INSERT INTO thumbs (digest, bytes, last_used, failed) VALUES ('legacy', 42, 10, 0)")
            db.version = 1
        }
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
}
