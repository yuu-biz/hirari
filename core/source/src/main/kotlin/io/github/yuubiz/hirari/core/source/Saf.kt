package io.github.yuubiz.hirari.core.source

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.yuubiz.hirari.core.model.normalizePath
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Copying a non-seekable document would leave less than [reserveBytes] free (docs/spec.md section 3.1). */
class NoRoomForCopyException(val reserveBytes: Long) : IOException("not enough free space to copy the document")

/**
 * Opens the document at [uri] for random access (docs/spec.md section 3.1). When the provider hands over a pipe
 * instead of a file, copies the document into [copyDir] first. The copy has no name on disk once it is open, so
 * it disappears when the source closes. [lastModifiedMs] is the provider's time, also for a copied document.
 * Blocks on Binder and disk, so never call it on the main thread.
 *
 * @throws java.io.FileNotFoundException when the document is gone.
 * @throws SecurityException when the permission to read it is gone.
 * @throws NoRoomForCopyException when the copy would leave less than [COPY_RESERVE_BYTES] free.
 * @throws IOException when reading fails.
 */
fun ContentResolver.openByteSource(uri: Uri, copyDir: File, lastModifiedMs: Long? = null): ByteSource {
    val descriptor = openFileDescriptor(uri, "r") ?: throw IOException("the provider returned no descriptor")
    if (descriptor.statSize >= 0) return descriptor.asByteSource(lastModifiedMs)
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
            FileChannelSource(copy.channel, lastModifiedMs)
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
 * [openByteSource] opens. [size] and [lastModifiedMs] are null when the provider does not report them.
 */
class DocumentChild(
    val uri: Uri,
    val documentId: String,
    val name: String,
    val mimeType: String?,
    val size: Long?,
    val lastModifiedMs: Long? = null,
) {
    val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
}

/**
 * The direct children of the folder [documentId] in the tree [treeUri], in the order the provider gives them,
 * from one query. Blocks on Binder, so never call it on the main thread. Cancelling [signal] stops the query and,
 * once the provider has answered, the reading of its rows.
 *
 * @throws SecurityException when the permission to the tree is gone.
 * @throws IOException when the provider gives no result.
 */
fun ContentResolver.listChildren(
    treeUri: Uri,
    documentId: String = DocumentsContract.getTreeDocumentId(treeUri),
    signal: CancellationSignal? = null,
): List<DocumentChild> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
    val cursor = query(childrenUri, CHILD_COLUMNS, null, null, null, signal) ?: throw IOException("the provider returned no cursor")
    return cursor.use {
        val children = ArrayList<DocumentChild>(it.count.coerceAtLeast(0))
        while (true) {
            signal?.throwIfCanceled()
            if (!it.moveToNext()) break
            val id = it.getString(0) ?: continue
            children += DocumentChild(
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                documentId = id,
                name = it.getString(1) ?: id,
                mimeType = it.getString(2),
                size = if (it.isNull(3)) null else it.getLong(3),
                lastModifiedMs = if (it.isNull(4)) null else it.getLong(4).takeIf { ms -> ms > 0 },
            )
        }
        children
    }
}

/** A folder of a document tree: its own document id and its direct children. */
class ListedFolder(val documentId: String, val children: List<DocumentChild>)

/**
 * The folder at [path] in the tree [treeUri] and its direct children, or null when a name on the way is missing.
 * A known [documentId] saves looking the path up. Blocks on Binder, so never call it on the main thread.
 * Cancelling the caller tells the provider to stop the query in flight, so a provider that never answers does not
 * hold the caller.
 *
 * @throws SecurityException when the permission to the tree is gone.
 * @throws IOException when the provider gives no result.
 */
suspend fun ContentResolver.listFolder(treeUri: Uri, path: String, documentId: String? = null): ListedFolder? = withCancellationSignal { signal ->
    val id = documentId ?: findDocumentId(treeUri, path, signal) ?: return@withCancellationSignal null
    ListedFolder(id, listChildren(treeUri, id, signal))
}

/**
 * The document id of [path] in the tree [treeUri], as [findDocumentId] finds it. Cancelling the caller tells the
 * provider to stop the query in flight, so a provider that never answers does not hold the caller. Blocks on Binder,
 * so never call it on the main thread.
 *
 * @throws SecurityException when the permission to the tree is gone.
 * @throws IOException when the provider gives no result.
 */
suspend fun ContentResolver.locateDocument(treeUri: Uri, path: String): String? = withCancellationSignal { signal ->
    findDocumentId(treeUri, path, signal)
}

/** What a provider reports about one document; a field is null when it does not report it. */
class DocumentInfo(val name: String?, val size: Long?, val lastModifiedMs: Long?)

/** Optional cache metadata, queried off the main thread. Failure leaves the time unknown, cancellation propagates. */
suspend fun ContentResolver.documentLastModifiedMs(uri: Uri): Long? = try {
    documentInfo(uri)?.lastModifiedMs
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/**
 * The name, size and modification time of the document at [uri], from one query. A provider that is no document
 * provider may lack the modification time. Blocks on Binder, so never call it on the main thread. Cancelling
 * the caller tells the provider to stop the query, so a provider that never answers does not hold the caller.
 *
 * @throws SecurityException when the permission to read it is gone.
 */
suspend fun ContentResolver.documentInfo(uri: Uri): DocumentInfo? = withCancellationSignal { signal ->
    val cursor = query(uri, null, null, null, null, signal) ?: return@withCancellationSignal null
    cursor.use {
        if (!it.moveToFirst()) return@withCancellationSignal null
        fun column(name: String) = it.getColumnIndex(name).takeIf { index -> index >= 0 && !it.isNull(index) }
        DocumentInfo(
            name = column(Document.COLUMN_DISPLAY_NAME)?.let(it::getString),
            size = column(Document.COLUMN_SIZE)?.let(it::getLong),
            lastModifiedMs = column(Document.COLUMN_LAST_MODIFIED)?.let(it::getLong)?.takeIf { ms -> ms > 0 },
        )
    }
}

/** Runs the blocking [block] on this thread, and cancels its [CancellationSignal] when the caller is cancelled. */
private suspend fun <T> withCancellationSignal(block: (CancellationSignal) -> T): T = suspendCancellableCoroutine { continuation ->
    val signal = CancellationSignal()
    continuation.invokeOnCancellation { signal.cancel() }
    try {
        continuation.resume(block(signal))
    } catch (e: Throwable) {
        continuation.resumeWithException(e)
    }
}

private val CHILD_COLUMNS = arrayOf(
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE,
    Document.COLUMN_SIZE,
    Document.COLUMN_LAST_MODIFIED,
)

/**
 * The document id of [path] inside the tree [treeUri], found one name at a time from the top of the tree, or
 * null when a name is missing. Document ids are the provider's own, so a path is the only lasting address of a
 * book in a place (docs/spec.md section 2.2). One query per folder on the way, so never call it on the main
 * thread.
 *
 * @throws SecurityException when the permission to the tree is gone.
 * @throws IOException when the provider gives no result.
 */
fun ContentResolver.findDocumentId(treeUri: Uri, path: String, signal: CancellationSignal? = null): String? {
    var id = DocumentsContract.getTreeDocumentId(treeUri)
    for (name in normalizePath(path).split('/').filter { it.isNotEmpty() }) {
        id = listChildren(treeUri, id, signal).firstOrNull { normalizePath(it.name) == name }?.documentId ?: return null
    }
    return id
}
