package io.github.yuubiz.hirari.core.source.save

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.os.ParcelFileDescriptor
import io.github.yuubiz.hirari.core.source.listChildren
import java.io.FileOutputStream
import java.io.IOException

/**
 * A folder of a document tree as the destination of a save (docs/spec.md section 4.5). Blocks on Binder and disk, so
 * never use it on the main thread. The tree needs a write grant.
 *
 * Files are made with the generic MIME type, so a provider that fixes the extension to the type keeps the name
 * `名前.part` as it is, and are then renamed. A provider that cannot rename a document cannot be saved to: the file is
 * deleted and [SaveDestinationException] is thrown before anything is written.
 */
class SafSaveFolder(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val documentId: String = DocumentsContract.getTreeDocumentId(treeUri),
) : SaveFolder {
    private val folderUri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override fun names(): Set<String> = resolver.listChildren(treeUri, documentId).mapTo(HashSet()) { it.name }

    override fun createFolder(name: String): SaveFolder {
        val created = createDocument(Document.MIME_TYPE_DIR, name)
        return SafSaveFolder(resolver, treeUri, DocumentsContract.getDocumentId(created))
    }

    override fun create(name: String): SaveFile {
        val uri = documentUri(createDocument(GENERIC_MIME, name))
        try {
            val flags = resolver.query(uri, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0
            if (flags and Document.FLAG_SUPPORTS_RENAME == 0) throw SaveDestinationException("the provider cannot rename a document")
            val descriptor = resolver.openFileDescriptor(uri, "w") ?: throw SaveDestinationException("the provider returned no descriptor")
            return SafSaveFile(resolver, uri, descriptor)
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw t
        }
    }

    override fun delete(name: String) {
        val child = resolver.listChildren(treeUri, documentId).firstOrNull { it.name == name } ?: return
        DocumentsContract.deleteDocument(resolver, child.uri)
    }

    private fun createDocument(mimeType: String, name: String): Uri =
        DocumentsContract.createDocument(resolver, folderUri, mimeType, name) ?: throw SaveDestinationException("the provider created no document")

    private fun documentUri(created: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getDocumentId(created))

    private companion object {
        const val GENERIC_MIME = "application/octet-stream"
    }
}

private class SafSaveFile(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val descriptor: ParcelFileDescriptor,
) : SaveFile {
    private val output = FileOutputStream(descriptor.fileDescriptor)
    private var open = true
    private var committed = false

    override val freeBytes: Long?
        get() = try {
            val fd = descriptor.fileDescriptor
            if (OsConstants.S_ISREG(Os.fstat(fd).st_mode)) Os.fstatvfs(fd).let { it.f_bavail * it.f_frsize } else null
        } catch (_: ErrnoException) {
            null
        }

    override fun write(buffer: ByteArray, length: Int) {
        try {
            output.write(buffer, 0, length)
        } catch (e: IOException) {
            if (e.isFull()) throw NoRoomException()
            throw e
        }
    }

    override fun commit(name: String) {
        close(sync = true)
        DocumentsContract.renameDocument(resolver, uri, name) ?: throw SaveDestinationException("the provider did not rename the document")
        committed = true
    }

    override fun discard() {
        if (committed) return
        runCatching { close(sync = false) }
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }

    private fun close(sync: Boolean) {
        if (!open) return
        open = false
        try {
            if (sync) runCatching { output.fd.sync() }
        } finally {
            try {
                output.close()
            } finally {
                descriptor.close()
            }
        }
    }
}

private fun IOException.isFull(): Boolean {
    val errno = (cause as? ErrnoException)?.errno
    return errno == OsConstants.ENOSPC || errno == OsConstants.EDQUOT
}
