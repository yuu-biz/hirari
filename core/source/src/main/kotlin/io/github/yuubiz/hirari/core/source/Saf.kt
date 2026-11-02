package io.github.yuubiz.hirari.core.source

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Copying a non-seekable document would leave less than [reserveBytes] free (docs/spec.md section 3.1). */
class NoRoomForCopyException(val reserveBytes: Long) : IOException("not enough free space to copy the document")

/**
 * Opens the document at [uri] for random access (docs/spec.md section 3.1). When the provider hands over a pipe
 * instead of a file, copies the document into [copyDir] first. The copy has no name on disk once it is open, so
 * it disappears when the source closes. Blocks on Binder and disk, so never call it on the main thread.
 *
 * @throws java.io.FileNotFoundException when the document is gone.
 * @throws SecurityException when the permission to read it is gone.
 * @throws NoRoomForCopyException when the copy would leave less than [COPY_RESERVE_BYTES] free.
 * @throws IOException when reading fails.
 */
fun ContentResolver.openByteSource(uri: Uri, copyDir: File): ByteSource {
    val descriptor = openFileDescriptor(uri, "r") ?: throw IOException("the provider returned no descriptor")
    if (descriptor.statSize >= 0) return descriptor.asByteSource()
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
        copyDir.mkdirs()
        val file = File.createTempFile("copy", null, copyDir)
        val copy = try {
            RandomAccessFile(file, "rw")
        } finally {
            file.delete()
        }
        try {
            val buffer = ByteArray(COPY_CHUNK)
            var sinceCheck = Long.MAX_VALUE
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (sinceCheck >= SPACE_CHECK_INTERVAL) {
                    if (copyDir.usableSpace < COPY_RESERVE_BYTES + SPACE_CHECK_INTERVAL) {
                        throw NoRoomForCopyException(COPY_RESERVE_BYTES)
                    }
                    sinceCheck = 0
                }
                copy.write(buffer, 0, read)
                sinceCheck += read
            }
            FileChannelSource(copy.channel)
        } catch (e: Throwable) {
            copy.close()
            throw e
        }
    }
}

/** The free space a copy leaves at least, so filling the cache never fills the disk. */
const val COPY_RESERVE_BYTES: Long = 64L shl 20

private const val COPY_CHUNK = 64 * 1024
private const val SPACE_CHECK_INTERVAL: Long = 1L shl 20

/**
 * A direct child of a folder in a document tree. [uri] is a document URI inside the tree, which
 * [openByteSource] opens. [size] is null when the provider does not report it.
 */
class DocumentChild(
    val uri: Uri,
    val documentId: String,
    val name: String,
    val mimeType: String?,
    val size: Long?,
) {
    val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
}

/**
 * The direct children of the folder [documentId] in the tree [treeUri], in the order the provider gives them,
 * from one query. Blocks on Binder, so never call it on the main thread.
 *
 * @throws SecurityException when the permission to the tree is gone.
 * @throws IOException when the provider gives no result.
 */
fun ContentResolver.listChildren(
    treeUri: Uri,
    documentId: String = DocumentsContract.getTreeDocumentId(treeUri),
): List<DocumentChild> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
    val cursor = query(childrenUri, CHILD_COLUMNS, null, null, null) ?: throw IOException("the provider returned no cursor")
    return cursor.use {
        val children = ArrayList<DocumentChild>(it.count.coerceAtLeast(0))
        while (it.moveToNext()) {
            val id = it.getString(0) ?: continue
            children += DocumentChild(
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                documentId = id,
                name = it.getString(1) ?: id,
                mimeType = it.getString(2),
                size = if (it.isNull(3)) null else it.getLong(3),
            )
        }
        children
    }
}

private val CHILD_COLUMNS = arrayOf(
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE,
    Document.COLUMN_SIZE,
)
