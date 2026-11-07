package io.github.yuubiz.hirari.core.source

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.AbstractCursor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TREE_AUTHORITY = "hirari.test.tree"

/** A tree whose root holds the folder `漫画`, which holds `1巻.zip`. */
class TreeProvider : ContentProvider() {
    @Volatile var hangsOn: String? = null
    val queryStarted = CountDownLatch(1)
    val queryCancelled = CountDownLatch(1)

    private val children = mapOf(
        "root" to listOf(arrayOf<Any?>("dir", "漫画", Document.MIME_TYPE_DIR, null, 1_000L, Document.FLAG_SUPPORTS_RENAME)),
        "dir" to listOf(arrayOf<Any?>("zip", "1巻.zip", "application/zip", 1234L, 2_000L, Document.FLAG_SUPPORTS_DELETE)),
    )

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor =
        query(uri, projection, selection, selectionArgs, sortOrder, null)

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor {
        val parent = DocumentsContract.getDocumentId(uri)
        if (parent == hangsOn) {
            queryStarted.countDown()
            val signal = checkNotNull(cancellationSignal) { "the query came without a cancellation signal" }
            signal.setOnCancelListener { queryCancelled.countDown() }
            check(queryCancelled.await(10, TimeUnit.SECONDS)) { "the provider was never told to stop" }
            signal.throwIfCanceled()
        }
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { children[parent].orEmpty().forEach(::addRow) }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

private const val SLOW_AUTHORITY = "hirari.test.slow"
private const val SLOW_ROWS = 100

/** A folder of [SLOW_ROWS] books whose cursor comes back at once and then waits on its second row. */
class SlowRowsProvider : ContentProvider() {
    val secondRow = CountDownLatch(1)
    val release = CountDownLatch(1)
    val cursor = SlowCursor()

    inner class SlowCursor : AbstractCursor() {
        @Volatile var moves = 0

        override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
            moves++
            if (newPosition == 1) {
                secondRow.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "the test never let the row go" }
            }
            return true
        }

        override fun getCount() = SLOW_ROWS
        override fun getColumnNames() = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        override fun getString(column: Int) = when (column) {
            0 -> "book$position"
            1 -> "$position.zip"
            2 -> "application/zip"
            else -> null
        }
        override fun getShort(column: Int): Short = 0
        override fun getInt(column: Int) = 0
        override fun getLong(column: Int) = 0L
        override fun getFloat(column: Int) = 0f
        override fun getDouble(column: Int) = 0.0
        override fun isNull(column: Int) = column >= 3
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor = cursor

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
class ListFolderTest {
    private val provider = Robolectric.buildContentProvider(TreeProvider::class.java).create(TREE_AUTHORITY).get()
    private val resolver: ContentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val tree = DocumentsContract.buildTreeDocumentUri(TREE_AUTHORITY, "root")

    @Test
    fun itLooksThePathUpAndListsTheFolder() = runBlocking {
        val folder = assertNotNull(resolver.listFolder(tree, "漫画"))
        assertEquals("dir", folder.documentId)
        assertEquals(listOf("1巻.zip"), folder.children.map { it.name })
        assertEquals(1234L, folder.children.single().size)
        assertEquals(Document.FLAG_SUPPORTS_DELETE, folder.children.single().flags)
        assertEquals(Document.FLAG_SUPPORTS_RENAME, resolver.listChildren(tree).single().flags)
        assertNull(resolver.listFolder(tree, "小説"))
    }

    @Test
    fun cancellingTheCallerStopsALookupThatDoesNotAnswer() = cancels(hangsOn = "root") { resolver.listFolder(tree, "漫画") }

    @Test
    fun cancellingTheCallerStopsAListingThatDoesNotAnswer() = cancels(hangsOn = "dir") { resolver.listFolder(tree, "漫画", documentId = "dir") }

    @Test
    fun cancellingTheCallerStopsReadingTheRowsAndClosesTheCursor() = runBlocking {
        val slow = Robolectric.buildContentProvider(SlowRowsProvider::class.java).create(SLOW_AUTHORITY).get()
        val listing = async(Dispatchers.IO) { resolver.listFolder(DocumentsContract.buildTreeDocumentUri(SLOW_AUTHORITY, "root"), "", documentId = "root") }
        assertTrue(slow.secondRow.await(10, TimeUnit.SECONDS))
        listing.cancel()
        slow.release.countDown()
        withTimeout(10_000) {
            assertFailsWith<CancellationException> { listing.await() }
        }
        assertTrue(slow.cursor.isClosed, "the cursor was left open")
        assertTrue(slow.cursor.moves < SLOW_ROWS, "read all ${slow.cursor.moves} rows after the cancel")
    }

    private fun cancels(hangsOn: String, call: suspend () -> Any?) = runBlocking {
        provider.hangsOn = hangsOn
        val started = CompletableDeferred<Unit>()
        val listing = async(Dispatchers.IO) {
            started.complete(Unit)
            call()
        }
        started.await()
        assertTrue(provider.queryStarted.await(10, TimeUnit.SECONDS))
        listing.cancel()
        withTimeout(10_000) {
            assertFailsWith<CancellationException> { listing.await() }
        }
        assertTrue(provider.queryCancelled.await(10, TimeUnit.SECONDS))
    }
}
