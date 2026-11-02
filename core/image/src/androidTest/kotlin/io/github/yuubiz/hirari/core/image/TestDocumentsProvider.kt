package io.github.yuubiz.hirari.core.image

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.concurrent.thread

/**
 * A document provider over `cacheDir/documents/`, for the SAF paths of the book opener. A document ID is a path
 * under that directory. Children come in reverse name order, so sorting is the opener's work. With [pipe] set,
 * documents open as pipes, as some cloud providers hand them over.
 */
class TestDocumentsProvider : DocumentsProvider() {
    private val base: File get() = File(context!!.cacheDir, "documents")

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE)).apply {
            newRow().add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_DOCUMENT_ID, ROOT)
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD).add(Root.COLUMN_TITLE, "test")
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: COLUMNS).apply { addDocument(documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: COLUMNS).apply {
            val children = file(parentDocumentId).listFiles() ?: throw FileNotFoundException(parentDocumentId)
            children.sortedByDescending { it.name }.forEach { addDocument("$parentDocumentId/${it.name}") }
        }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = file(documentId)
        if (!file.isFile) throw FileNotFoundException(documentId)
        if (!pipe) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread(name = "pipe-writer") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out -> file.inputStream().use { it.copyTo(out) } }
            } catch (_: IOException) {
            }
        }
        return read
    }

    private fun file(documentId: String) = File(base, documentId)

    private fun MatrixCursor.addDocument(documentId: String) {
        val file = file(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        newRow().add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            .add(Document.COLUMN_FLAGS, 0)
    }

    companion object {
        const val AUTHORITY = "io.github.yuubiz.hirari.core.image.test.documents"
        const val ROOT = "root"

        @Volatile
        var pipe = false

        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
        )
    }
}
