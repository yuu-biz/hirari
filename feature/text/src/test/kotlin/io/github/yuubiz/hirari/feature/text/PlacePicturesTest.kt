package io.github.yuubiz.hirari.feature.text

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.text.Figure
import io.github.yuubiz.hirari.core.text.parseAozora
import io.github.yuubiz.hirari.core.text.resolvePicturePath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PICTURE_AUTHORITY = "hirari.test.place.pictures"

class PictureTreeProvider : ContentProvider() {
    val files = mutableMapOf<String, File>()
    val opened = mutableListOf<String>()
    var reportSize = true
    var beforeOpenPicture: ((CancellationSignal?) -> Unit)? = null

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE)
        val id = DocumentsContract.getDocumentId(uri)
        val children = uri.lastPathSegment == "children"
        val ids = if (children) {
            if (id == "root") listOf("dir") else files.keys.filter { it.substringBeforeLast('/', "") == id }
        } else listOf(id)
        return MatrixCursor(columns).apply {
            for (child in ids) addRow(columns.map<String, Any?> { column ->
                when (column) {
                    Document.COLUMN_DOCUMENT_ID -> child
                    Document.COLUMN_DISPLAY_NAME -> child.substringAfterLast('/')
                    Document.COLUMN_MIME_TYPE -> if (child == "dir") Document.MIME_TYPE_DIR else "application/octet-stream"
                    Document.COLUMN_SIZE -> if (reportSize) files[child]?.length() else null
                    else -> null
                }
            }.toTypedArray())
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = DocumentsContract.getDocumentId(uri)
        opened += id
        return ParcelFileDescriptor.open(checkNotNull(files[id]), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor {
        if (DocumentsContract.getDocumentId(uri).endsWith(".png")) beforeOpenPicture?.invoke(signal)
        return AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor =
        openAssetFile(uri, "r", signal)

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
class PlacePicturesTest {
    @get:Rule val temp = TemporaryFolder()
    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val provider = Robolectric.buildContentProvider(PictureTreeProvider::class.java).create(PICTURE_AUTHORITY).get()
    private val saf = LibraryLocation.SafTree(LocationId(1), "shelf", UriString("content://$PICTURE_AUTHORITY/tree/root"))
    private val text = "題\n［＃挿絵（図１.png）入る］\n本文は読める。"
    private val image = byteArrayOf(1, 2, 3, 4)

    private fun safSources(budget: Int = 32): TextSources {
        provider.files["dir/story.txt"] = temp.newFile().apply { writeText(text) }
        provider.files["dir/図１.png"] = temp.newFile().apply { writeBytes(image) }
        return TextSources(resolver, OnePlace(saf), { _, _ -> error("no server") }, temp.newFolder(), budget) { error("no archive") }
    }

    private suspend fun illustration(opened: TextOpenResult.Opened): ByteArray? {
        val figure = parseAozora(opened.bytes.toString(Charsets.UTF_8)).blocks.filterIsInstance<Figure>().single()
        val path = assertNotNull(resolvePicturePath(opened.text.path, figure.path))
        return assertNotNull(opened.text.pictures).read(path)
    }

    @Test
    fun `TX03 a SAF place text loads its sibling with fullwidth digits`() = runBlocking {
        val opened = assertIs<TextOpenResult.Opened>(opener(TextBookRef.InLocation(saf.id, "dir/story.txt"), safSources()).open())
        assertEquals("dir/story.txt", opened.text.path)
        assertContentEquals(image, illustration(opened))
        assertEquals(listOf("dir/story.txt", "dir/図１.png"), provider.opened)
        assertNull(opened.text.pictures!!.read("../../outside.png"))
        assertEquals(2, provider.opened.size)
    }

    @Test
    fun `TX01 an over budget SAF illustration is refused before opening its stream`() = runBlocking {
        val opened = assertIs<TextOpenResult.Opened>(opener(TextBookRef.InLocation(saf.id, "dir/story.txt"), safSources(budget = 3)).open())
        assertNull(illustration(opened))
        assertEquals(listOf("dir/story.txt"), provider.opened)
        assertTrue(opened.bytes.toString(Charsets.UTF_8).endsWith("本文は読める。"))
    }

    @Test
    fun `TX01 a SAF illustration with unknown size is still bounded`() = runBlocking {
        val sources = safSources(budget = 3)
        provider.reportSize = false
        val opened = assertIs<TextOpenResult.Opened>(opener(TextBookRef.InLocation(saf.id, "dir/story.txt"), sources).open())
        assertNull(illustration(opened))
        assertEquals(listOf("dir/story.txt", "dir/図１.png"), provider.opened)
    }

    @Test
    fun `TX03 an SMB place text reads its sibling through the same share and closes it`() = runBlocking {
        val smb = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "novels", null, null, null)
        val asked = mutableListOf<String>()
        var closed = 0
        val files = SmbFiles { place, path ->
            assertEquals(smb, place)
            asked += path
            val bytes = if (path == "dir/story.txt") text.toByteArray() else image
            object : ByteSource {
                override val size = bytes.size.toLong()
                override fun read(offset: Long, dst: ByteBuffer): Int {
                    val count = minOf(2, dst.remaining(), bytes.size - offset.toInt())
                    if (count == 0) return -1
                    dst.put(bytes, offset.toInt(), count)
                    return count
                }
                override fun close() { closed++ }
            }
        }
        val sources = TextSources(resolver, OnePlace(smb), files, temp.newFolder()) { error("no archive") }
        val opened = assertIs<TextOpenResult.Opened>(opener(TextBookRef.InLocation(smb.id, "dir/story.txt"), sources).open())
        assertEquals("dir/story.txt", opened.text.path)
        assertContentEquals(image, illustration(opened))
        assertNull(opened.text.pictures!!.read("../../outside.png"))
        assertEquals(listOf("dir/story.txt", "dir/図１.png"), asked)
        assertEquals(2, closed)
    }

    @Test
    fun `TX01 an over budget SMB file is refused before the first read`() = runBlocking {
        var reads = 0
        val file = object : ByteSource {
            override val size = pictureBudgetBytes() + 1L
            override fun read(offset: Long, dst: ByteBuffer): Int { reads++; error("must not read") }
            override fun close() = Unit
        }
        assertNull(readPicture(file, pictureBudgetBytes()))
        assertEquals(0, reads)
    }

    @Test
    fun `a cancelled SMB picture stops before the next chunk and closes its source`() = runBlocking {
        val job = Job()
        var reads = 0
        var closed = false
        val file = object : ByteSource {
            override val size = 8L
            override fun read(offset: Long, dst: ByteBuffer): Int {
                reads++
                dst.put(1.toByte())
                job.cancel()
                return 1
            }
            override fun close() { closed = true }
        }
        assertFailsWith<CancellationException> {
            withContext(job) { file.use { readPicture(it, 8) } }
        }
        assertEquals(1, reads)
        assertTrue(closed)
    }

    @Test
    fun `a cancelled stream stops before the next chunk and closes`() {
        val job = Job()
        var reads = 0
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = error("use chunk reads")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                buffer[offset] = 1
                job.cancel()
                return 1
            }
            override fun close() { closed = true }
        }
        assertFailsWith<CancellationException> { input.use { readCapped(it, 8, job) } }
        assertEquals(1, reads)
        assertTrue(closed)
    }

    @Test
    fun `cancelling a SAF picture releases a provider waiting in open`() = runBlocking {
        val sources = safSources()
        val opened = assertIs<TextOpenResult.Opened>(opener(TextBookRef.InLocation(saf.id, "dir/story.txt"), sources).open())
        val entered = CompletableDeferred<Unit>()
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.beforeOpenPicture = { signal ->
            signal?.setOnCancelListener { cancelled.countDown(); release.countDown() }
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "provider was not released" }
            signal?.throwIfCanceled()
        }
        val load = launch(Dispatchers.IO) { illustration(opened) }
        try {
            withTimeout(5_000) { entered.await() }
            load.cancel()
            assertTrue(withContext(Dispatchers.IO) { cancelled.await(5, TimeUnit.SECONDS) })
        } finally {
            release.countDown()
            load.cancelAndJoin()
            opened.text.close()
        }
        assertEquals(listOf("dir/story.txt"), provider.opened)
    }
}
