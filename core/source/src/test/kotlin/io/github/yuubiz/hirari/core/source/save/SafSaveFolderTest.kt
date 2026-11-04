package io.github.yuubiz.hirari.core.source.save

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.source.smb.FakeConnector
import io.github.yuubiz.hirari.core.source.smb.SmbLogin
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileDescriptor
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val SAVE_AUTHORITY = "hirari.test.save"

/** The value of `DocumentsContract.EXTRA_URI`, which the SDK keeps hidden. */
private const val EXTRA_URI = "uri"

/** A writable tree in memory: createDocument, renameDocument and deleteDocument as a document provider answers them. */
class SaveProvider : ContentProvider() {
    class Doc(val id: String, val parent: String?, var name: String, val mime: String, val file: File?)

    val docs = linkedMapOf("root" to Doc("root", null, "root", Document.MIME_TYPE_DIR, null))
    var renames = true
    private var counter = 0
    private val dir = createTempDirectory("save-provider").toFile()

    fun childrenOf(id: String) = docs.values.filter { it.parent == id }

    fun named(name: String): Doc? = docs.values.firstOrNull { it.name == name }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
        val id = DocumentsContract.getDocumentId(uri)
        val rows = if ("children" in uri.pathSegments) childrenOf(id) else listOfNotNull(docs[id])
        return MatrixCursor(columns).apply {
            rows.forEach { doc ->
                addRow(
                    columns.map<String, Any?> {
                        when (it) {
                            Document.COLUMN_DOCUMENT_ID -> doc.id
                            Document.COLUMN_DISPLAY_NAME -> doc.name
                            Document.COLUMN_MIME_TYPE -> doc.mime
                            Document.COLUMN_SIZE -> doc.file?.length()
                            Document.COLUMN_FLAGS -> if (renames) Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_WRITE else Document.FLAG_SUPPORTS_WRITE
                            else -> null
                        }
                    }.toTypedArray(),
                )
            }
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uri = checkNotNull(extras).getParcelable<Uri>(EXTRA_URI)!!
        val id = DocumentsContract.getDocumentId(uri)
        return when (method) {
            "android:createDocument" -> {
                val created = "d${++counter}"
                val mime = extras.getString(Document.COLUMN_MIME_TYPE)!!
                val file = if (mime == Document.MIME_TYPE_DIR) null else File(dir, created).also { it.writeBytes(ByteArray(0)) }
                docs[created] = Doc(created, id, extras.getString(Document.COLUMN_DISPLAY_NAME)!!, mime, file)
                Bundle().apply { putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUri(SAVE_AUTHORITY, created)) }
            }
            "android:renameDocument" -> {
                docs.getValue(id).name = extras.getString(Document.COLUMN_DISPLAY_NAME)!!
                Bundle().apply { putParcelable(EXTRA_URI, uri) }
            }
            "android:deleteDocument" -> {
                fun drop(doc: String) {
                    childrenOf(doc).forEach { drop(it.id) }
                    docs.remove(doc)?.file?.delete()
                }
                drop(id)
                Bundle()
            }
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(docs.getValue(DocumentsContract.getDocumentId(uri)).file!!, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
class SafSaveFolderTest {
    private val provider = Robolectric.buildContentProvider(SaveProvider::class.java).create(SAVE_AUTHORITY).get()
    private val resolver: ContentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val tree = DocumentsContract.buildTreeDocumentUri(SAVE_AUTHORITY, "root")
    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a file is written as a part and takes its name when committed`() {
        val root = SafSaveFolder(resolver, tree)
        val file = root.create("vol1.cbz.part")
        assertEquals(setOf("vol1.cbz.part"), root.names())
        file.write(byteArrayOf(1, 2, 3, 4), 3)
        file.commit("vol1.cbz")
        assertEquals(setOf("vol1.cbz"), root.names())
        assertContentEquals(byteArrayOf(1, 2, 3), provider.named("vol1.cbz")!!.file!!.readBytes())
        assertEquals("application/octet-stream", provider.named("vol1.cbz")!!.mime)
    }

    @Test
    fun `discard removes only the created document`() {
        val root = SafSaveFolder(resolver, tree)
        root.create("a.part").discard()
        root.create("b.part").also { it.write(byteArrayOf(1), 1) }.discard()
        root.create("keep.zip.part").commit("keep.zip")
        root.createFolder("folder").create("inner.zip").commit("inner.zip")
        assertEquals(setOf("keep.zip", "folder"), root.names())
        assertEquals(4, provider.docs.size)
        assertEquals(provider.named("folder")!!.id, provider.named("inner.zip")!!.parent)
    }

    @Test
    fun `two folders of the same document are equal, so saves through each see the other's names`() {
        val root = SafSaveFolder(resolver, tree)
        val series = root.createFolder("series")
        assertEquals<SaveFolder>(root, SafSaveFolder(resolver, tree))
        assertEquals(root.hashCode(), SafSaveFolder(resolver, tree).hashCode())
        assertEquals<SaveFolder>(series, SafSaveFolder(resolver, tree, provider.named("series")!!.id))
        assertNotEquals<SaveFolder>(root, series)
    }

    @Test
    fun `a folder made by createFolder holds the files made in it`() {
        val root = SafSaveFolder(resolver, tree)
        val folder = root.createFolder("series")
        folder.create("a.zip.part").commit("a.zip")
        assertEquals(setOf("a.zip"), folder.names())
        assertEquals(setOf("series"), root.names())
        assertEquals(Document.MIME_TYPE_DIR, provider.named("series")!!.mime)
    }

    @Test
    fun `a provider that cannot rename is refused before anything is written`() {
        provider.renames = false
        val root = SafSaveFolder(resolver, tree)
        assertFailsWith<CannotRenameException> { root.create("vol1.cbz.part") }
        assertEquals(emptySet(), root.names())
    }

    @Test
    fun `a save into a provider that cannot rename fails for want of permission`() {
        provider.renames = false
        val server = SmbServer(FakeConnector(mapOf("vol1.cbz" to byteArrayOf(1, 2, 3))), SmbLogin.Guest, scope, emptyFlow(), Dispatchers.IO, 15.seconds)
        val outcome = runBlocking { server.saveTo("vol1.cbz", false, SafSaveFolder(resolver, tree)) }
        assertEquals(SaveOutcome.Failed(SaveFailure.NoPermission), outcome)
        assertEquals(setOf("root"), provider.docs.keys)
    }

    private fun saveSyncingWith(fsync: (FileDescriptor) -> Unit): SaveOutcome {
        val server = SmbServer(FakeConnector(mapOf("vol1.cbz" to byteArrayOf(1, 2, 3))), SmbLogin.Guest, scope, emptyFlow(), Dispatchers.IO, 15.seconds)
        return runBlocking { server.saveTo("vol1.cbz", false, SafSaveFolder(resolver, tree, fsync = fsync)) }
    }

    @Test
    fun `a file the storage fails to sync is not renamed and is deleted`() {
        assertEquals(SaveOutcome.Failed(SaveFailure.WriteFailed), saveSyncingWith { throw ErrnoException("fsync", OsConstants.EIO) })
        assertEquals(setOf("root"), provider.docs.keys)
        assertEquals(SaveOutcome.Failed(SaveFailure.NoRoom), saveSyncingWith { throw ErrnoException("fsync", OsConstants.ENOSPC) })
        assertEquals(setOf("root"), provider.docs.keys)
    }

    @Test
    fun `a file that cannot be synced, as a pipe, is still saved`() {
        assertEquals(SaveOutcome.Saved("vol1.cbz", 1, 3), saveSyncingWith { throw ErrnoException("fsync", OsConstants.EINVAL) })
        assertContentEquals(byteArrayOf(1, 2, 3), provider.named("vol1.cbz")!!.file!!.readBytes())
    }

    @Test
    fun `a whole copy from the server lands in the tree`() {
        val bytes = ByteArray(2 * SAVE_BLOCK + 5) { (it * 13).toByte() }
        val files = mapOf("shelf/vol1.cbz" to bytes, "shelf/extras/cover.jpg" to byteArrayOf(8, 9))
        val server = SmbServer(FakeConnector(files), SmbLogin.Guest, scope, emptyFlow(), Dispatchers.IO, 15.seconds)
        val root = SafSaveFolder(resolver, tree)
        val outcome = runBlocking { server.saveTo("shelf", true, root) }
        assertEquals(SaveOutcome.Saved("shelf", 1, bytes.size.toLong()), outcome)
        val shelf = provider.named("shelf")!!
        assertContentEquals(bytes, provider.childrenOf(shelf.id).single { it.name == "vol1.cbz" }.file!!.readBytes())
        assertTrue(provider.docs.values.none { it.name.endsWith(SAVE_PART_SUFFIX) })
    }

    @Test
    fun `an existing part file survives a successful save`() = preservesExistingPart(directory = false, end = End.Success)

    @Test
    fun `an existing part directory survives a successful save`() = preservesExistingPart(directory = true, end = End.Success)

    @Test
    fun `an existing part file survives cancellation and the created part is removed`() = preservesExistingPart(directory = false, end = End.Cancelled)

    @Test
    fun `an existing part directory survives cancellation and the created part is removed`() = preservesExistingPart(directory = true, end = End.Cancelled)

    @Test
    fun `an existing part file survives a failed save`() = preservesExistingPart(directory = false, end = End.Failed)

    @Test
    fun `an existing part directory survives a failed save`() = preservesExistingPart(directory = true, end = End.Failed)

    private enum class End { Success, Cancelled, Failed }

    private fun preservesExistingPart(directory: Boolean, end: End) {
        val root = SafSaveFolder(resolver, tree)
        val original = byteArrayOf(7, 8, 9)
        val existingName = "book.zip.part"
        val owner = if (directory) root.createFolder(existingName) else root
        val contentName = if (directory) "keep.txt" else existingName
        owner.create(contentName).also { it.write(original, original.size) }.commit(contentName)
        val existing = provider.named(existingName)!!
        val content = provider.named(contentName)!!
        val destination = SafSaveFolder(resolver, tree, fsync = {
            if (end == End.Failed) throw ErrnoException("fsync", OsConstants.EIO)
        })
        val bytes = ByteArray(SAVE_BLOCK + 1) { 42 }
        val server = SmbServer(FakeConnector(mapOf("book.zip" to bytes)), SmbLogin.Guest, scope, emptyFlow(), Dispatchers.IO, 15.seconds)
        fun save() = runBlocking {
            server.saveTo("book.zip", false, destination) {
                if (it.bytesDone > 0 && it.filesDone == 0) {
                    assertTrue(provider.named("book.zip.1.part") != null)
                    if (end == End.Cancelled) throw CancellationException("cancel after writing a block")
                }
            }
        }
        when (end) {
            End.Success -> {
                assertEquals(SaveOutcome.Saved("book.zip", 1, bytes.size.toLong()), save())
                assertContentEquals(bytes, provider.named("book.zip")!!.file!!.readBytes())
            }
            End.Cancelled -> assertFailsWith<CancellationException> { save() }
            End.Failed -> assertEquals(SaveOutcome.Failed(SaveFailure.WriteFailed), save())
        }
        assertEquals(existing, provider.named(existingName))
        assertEquals(content, provider.named(contentName))
        assertContentEquals(original, content.file!!.readBytes())
        assertEquals(if (end == End.Success) setOf(existingName, "book.zip") else setOf(existingName), root.names())
        assertTrue(provider.docs.values.none { it.name == "book.zip.1.part" })
    }
}
