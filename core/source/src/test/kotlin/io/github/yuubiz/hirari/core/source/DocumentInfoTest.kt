package io.github.yuubiz.hirari.core.source

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
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
import kotlin.test.assertTrue

private const val AUTHORITY = "hirari.test.documents"

class DocumentProvider : ContentProvider() {
    @Volatile var hangs = false
    val queryStarted = CountDownLatch(1)
    val queryCancelled = CountDownLatch(1)

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor =
        query(uri, projection, selection, selectionArgs, sortOrder, null)

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor {
        if (hangs) {
            queryStarted.countDown()
            val signal = checkNotNull(cancellationSignal) { "the query came without a cancellation signal" }
            signal.setOnCancelListener { queryCancelled.countDown() }
            check(queryCancelled.await(10, TimeUnit.SECONDS)) { "the provider was never told to stop" }
            signal.throwIfCanceled()
        }
        return MatrixCursor(arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED))
            .apply { addRow(arrayOf<Any>("book.zip", 1234L, 5_000L)) }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
class DocumentInfoTest {
    private val provider = Robolectric.buildContentProvider(DocumentProvider::class.java).create(AUTHORITY).get()
    private val resolver: ContentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val uri = Uri.parse("content://$AUTHORITY/document/1")

    @Test
    fun itReadsTheNameTheSizeAndTheModificationTime() = runBlocking {
        val info = assertNotNull(resolver.documentInfo(uri))
        assertEquals("book.zip", info.name)
        assertEquals(1234L, info.size)
        assertEquals(5_000L, info.lastModifiedMs)
    }

    @Test
    fun cancellingTheCallerStopsAQueryThatDoesNotAnswer() = runBlocking {
        provider.hangs = true
        val started = CompletableDeferred<Unit>()
        val call = async(Dispatchers.IO) {
            started.complete(Unit)
            resolver.documentInfo(uri)
        }
        started.await()
        assertTrue(provider.queryStarted.await(10, TimeUnit.SECONDS))
        call.cancel()
        withTimeout(10_000) {
            assertFailsWith<CancellationException> { call.await() }
        }
        assertTrue(provider.queryCancelled.await(10, TimeUnit.SECONDS))
    }
}
