package io.github.yuubiz.hirari.core.image

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.yuubiz.hirari.core.archive.ArchiveFailure
import io.github.yuubiz.hirari.core.archive.ArchiveFormat
import io.github.yuubiz.hirari.core.archive.ArchiveSession
import io.github.yuubiz.hirari.core.archive.EntryResult
import io.github.yuubiz.hirari.core.archive.OpenResult
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.FallbackCharset
import io.github.yuubiz.hirari.core.model.ImageType
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.NoRoomForCopyException
import io.github.yuubiz.hirari.core.source.documentLastModifiedMs
import io.github.yuubiz.hirari.core.source.listChildren
import io.github.yuubiz.hirari.core.source.openByteSource
import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import kotlin.coroutines.cancellation.CancellationException

sealed interface BookResult {
    class Opened(val book: OpenBook) : BookResult

    data class Failed(val reason: BookFailure) : BookResult
}

/** Why a book did not open. */
enum class BookFailure {
    /** The document or folder no longer exists. */
    NotFound,

    /** The permission to read it is gone. */
    NoPermission,

    /** The provider cannot seek and the copy does not fit (docs/spec.md section 3.1). */
    NoRoomForCopy,

    /** A password protects the whole book: a PDF with a user password or an archive with encrypted headers (3.8). */
    Encrypted,
    Corrupt,
    Truncated,
    ReadError,
}

/**
 * How the entry names of an archive are decoded (docs/spec.md section 3.4): in [chosen], the book's
 * `BookOverrides.nameCharset`, when the user picked one, else in the charset the names point to, else in
 * [undetermined], the setting `fallbackCharset`. The archive list decodes them the same way, so both put the pages
 * in one order.
 */
data class EntryNaming(val chosen: Charset? = null, val undetermined: FallbackCharset = FallbackCharset.Cp932)

/** One image file of a folder book. [open] blocks, and is called on [Dispatchers.IO]. */
class FolderFile(val name: String, val size: Long?, val open: () -> ByteSource)

/** Opens books for the reader. Every function returns a [BookResult] and throws nothing but cancellation. */
object Books {
    /**
     * Opens the archive in [source], which the book owns from here on. A solid archive is extracted into
     * [extractDir], the book's directory under `cacheDir/extract/` (docs/spec.md section 3.3), when it is given.
     * The entries of any other archive go into [stash] once read, when it is given (section 5.3).
     */
    suspend fun openArchive(
        source: ByteSource,
        decoding: ImageDecoding,
        extractDir: File? = null,
        naming: EntryNaming = EntryNaming(),
        stash: DiskStash? = null,
    ): BookResult {
        val session = when (val opened = ArchiveSession.open(source, extractDir)) {
            is OpenResult.Failed -> return BookResult.Failed(opened.reason.bookFailure())
            OpenResult.Encrypted -> return BookResult.Failed(BookFailure.Encrypted)
            is OpenResult.Opened -> opened.session
        }
        return try {
            val charset = session.nameCharset(naming.chosen, Charset.forName(naming.undetermined.charsetName))
            val pages = when (val listed = archivePages(session.entries, session.solid, { session.name(it, charset) }, session::readPrefix)) {
                is Safely.Done -> listed.value
                is Safely.Failed -> {
                    session.close()
                    return BookResult.Failed(listed.reason)
                }
            }
            val extracted = session.solid && extractDir != null
            val bytes = ArchivePages(session).let { if (stash != null && !extracted) StashedPages(it, stash) else it }
            BookResult.Opened(OpenBook(BookContent.Images(bytes), session.format.container(), sortedPages(pages), session.indexError?.pageFailure()?.reason, decoding))
        } catch (_: OutOfMemoryError) {
            session.close()
            BookResult.Failed(BookFailure.ReadError)
        } catch (e: Throwable) {
            session.close()
            throw e
        }
    }

    /**
     * Opens the image files of a folder (docs/spec.md section 3.10). [files] are the files directly inside it. A file
     * that cannot be told for a broken connection fails the open (section 6.5), since leaving it out would drop a page.
     */
    suspend fun openFolder(files: List<FolderFile>, decoding: ImageDecoding): BookResult {
        val pages = try {
            withContext(Dispatchers.IO) {
                files.mapIndexedNotNull { i, file ->
                    val page = PageEntry(-1, i, file.name.toByteArray(Charsets.UTF_8), file.name, file.size, sniffed = null)
                    when (pageCandidate(file.name, isDirectory = false)) {
                        PageCandidate.Image -> page
                        PageCandidate.Excluded -> null
                        PageCandidate.Sniff -> sniffPrefix(file)?.let { page.copy(sniffed = it) }
                    }
                }
            }
        } catch (_: ConnectionLostException) {
            return BookResult.Failed(BookFailure.ReadError)
        }
        return BookResult.Opened(OpenBook(BookContent.Images(FolderPages(files)), Container.Folder, sortedPages(pages), indexError = null, decoding))
    }

    /**
     * Opens the image in [source], which the book owns from here on, as a book of one page named `1`, as a PDF names
     * its pages (docs/spec.md sections 3.2 and 3.10). [type] is what its first bytes show.
     */
    fun openImage(source: ByteSource, type: ImageType, decoding: ImageDecoding): BookResult {
        val page = PageEntry(0, 0, byteArrayOf('1'.code.toByte()), "1", source.size, sniffed = type)
        return BookResult.Opened(OpenBook(BookContent.Images(SinglePage(source)), Container.Image, listOf(page), indexError = null, decoding))
    }

    /**
     * Opens the book in [source], which the book owns from here on: a single image when its first bytes show an
     * image, a PDF when `%PDF-` starts within its first 1024 bytes (docs/spec.md section 3.2), and otherwise an
     * archive, extracted into [extractDir] when solid, and its entries kept in [stash] otherwise.
     */
    suspend fun openBook(
        source: ByteSource,
        decoding: ImageDecoding,
        extractDir: File? = null,
        naming: EntryNaming = EntryNaming(),
        stash: DiskStash? = null,
    ): BookResult {
        val read = try {
            safely { readPrefix(source, PDF_SNIFF_BYTES) }
        } catch (e: CancellationException) {
            source.close()
            throw e
        }
        val prefix = when (read) {
            is Safely.Done -> read.value
            is Safely.Failed -> {
                source.close()
                return BookResult.Failed(read.reason)
            }
        }
        sniffImage(prefix)?.let { return openImage(source, it, decoding) }
        return if (isPdf(prefix)) openPdf(source, decoding) else openArchive(source, decoding, extractDir, naming, stash)
    }

    /** Opens the PDF in [source], which the book owns from here on (docs/spec.md section 3.9). */
    suspend fun openPdf(source: ByteSource, decoding: ImageDecoding): BookResult {
        val storage = decoding.storage ?: run {
            source.close()
            return BookResult.Failed(BookFailure.ReadError)
        }
        val document = when (val opened = PdfDocument.open(source, storage)) {
            is PdfDocument.Opened.Document -> opened.document
            is PdfDocument.Opened.Failed -> return BookResult.Failed(opened.reason)
        }
        val pages = (0 until document.pageCount).map { i ->
            val name = "${i + 1}"
            PageEntry(i, i, name.toByteArray(Charsets.US_ASCII), name, declaredSize = null, sniffed = ImageType.PdfPage)
        }
        return BookResult.Opened(OpenBook(BookContent.Pdf(document), Container.Pdf, pages, indexError = null, decoding))
    }

    /**
     * Opens the archive or PDF document at [uri]. A provider that cannot seek gets the document copied into
     * [copyDir]. A solid archive is extracted into [extractDir] when it is given. [naming] decodes an archive's names.
     */
    suspend fun openDocument(
        resolver: ContentResolver,
        uri: Uri,
        copyDir: File,
        decoding: ImageDecoding,
        extractDir: File? = null,
        naming: EntryNaming = EntryNaming(),
    ): BookResult {
        val modified = withContext(Dispatchers.IO) { resolver.documentLastModifiedMs(uri) }
        val source = when (val opened = safely(release = ByteSource::close) {
            resolver.openByteSource(uri, copyDir, modified)
        }) {
            is Safely.Done -> opened.value
            is Safely.Failed -> return BookResult.Failed(opened.reason)
        }
        return openBook(source, decoding, extractDir, naming)
    }

    /**
     * Opens the folder [documentId] of the tree [treeUri], or the root of the tree when it is null, as a folder
     * book. Sub-folders are not part of it.
     */
    suspend fun openTreeFolder(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String?,
        copyDir: File,
        decoding: ImageDecoding,
    ): BookResult {
        val listed = safely { resolver.listChildren(treeUri, documentId ?: DocumentsContract.getTreeDocumentId(treeUri)) }
        val children = when (listed) {
            is Safely.Done -> listed.value
            is Safely.Failed -> return BookResult.Failed(listed.reason)
        }
        val files = children.filterNot { it.isDirectory }.map { child ->
            FolderFile(child.name, child.size) { resolver.openByteSource(child.uri, copyDir) }
        }
        return openFolder(files, decoding)
    }

    /**
     * Opens the folder at [path] of [server], counted from the root of the share, as a folder book (docs/spec.md
     * sections 3.10 and 6.5). Every file the book reads goes through [counted], which counts it for 取得の状況.
     */
    suspend fun openServerFolder(
        server: SmbServer,
        path: String,
        decoding: ImageDecoding,
        counted: (ByteSource) -> ByteSource = { it },
    ): BookResult {
        val entries = try {
            server.list(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BookResult.Failed(e.bookFailure())
        }
        val files = entries.filterNot { it.isDirectory }.map { entry ->
            FolderFile(entry.name, entry.size) { counted(server.open(entry.path)) }
        }
        return openFolder(files, decoding)
    }

    /** The image type the first bytes of [file] show, or null when they show none or [file] cannot be opened. */
    private fun sniffPrefix(file: FolderFile) = try {
        file.open().use { source -> sniffImage(readPrefix(source, SNIFF_BYTES)) }
    } catch (e: ConnectionLostException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Up to the first [count] bytes of [source]. */
    private fun readPrefix(source: ByteSource, count: Int): ByteArray {
        val prefix = ByteBuffer.allocate(minOf(count.toLong(), source.size).toInt())
        while (prefix.hasRemaining() && source.read(prefix.position().toLong(), prefix) > 0) Unit
        return prefix.array().copyOf(prefix.position())
    }

    internal sealed interface Safely<out T> {
        class Done<T>(val value: T) : Safely<T>

        class Failed(val reason: BookFailure) : Safely<Nothing>
    }

    /**
     * Runs [block] on [Dispatchers.IO] and turns what it throws into a [BookFailure]. When the caller is cancelled
     * after [block] returned, [release] gets what it returned, since the caller never will.
     */
    internal suspend fun <T> safely(release: (T) -> Unit = {}, block: () -> T): Safely<T> {
        var done: Safely.Done<T>? = null
        try {
            return withContext(Dispatchers.IO) {
                try {
                    Safely.Done(block()).also { done = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Safely.Failed(e.bookFailure())
                }
            }
        } catch (e: CancellationException) {
            done?.let { release(it.value) }
            throw e
        }
    }

    private const val SNIFF_BYTES = 32
    private const val PDF_SNIFF_BYTES = 1024
}

/** Why a book did not open, from what the provider or the copy threw. */
internal fun Exception.bookFailure(): BookFailure = when (this) {
    is FileNotFoundException, is IllegalArgumentException -> BookFailure.NotFound
    is SecurityException -> BookFailure.NoPermission
    is NoRoomForCopyException -> BookFailure.NoRoomForCopy
    else -> BookFailure.ReadError
}

/** An archive with no entries has no format to tell, and counts as a ZIP. */
private fun ArchiveFormat?.container(): Container = when (this) {
    null, ArchiveFormat.Zip -> Container.Zip
    ArchiveFormat.Rar, ArchiveFormat.Rar5 -> Container.Rar
    ArchiveFormat.SevenZip -> Container.SevenZip
    ArchiveFormat.Tar -> Container.Tar
}

internal fun ArchiveFailure.bookFailure(): BookFailure = when (this) {
    ArchiveFailure.Corrupt -> BookFailure.Corrupt
    ArchiveFailure.Truncated -> BookFailure.Truncated
    ArchiveFailure.ReadError, ArchiveFailure.ConnectionLost -> BookFailure.ReadError
}

private class ArchivePages(private val session: ArchiveSession) : PageBytes {
    override suspend fun prefix(page: PageEntry, count: Int): PageResult<ByteArray> =
        when (val result = session.readPrefix(page.entryIndex, count)) {
            is EntryResult.Data -> PageResult.Ready(result.bytes)
            else -> result.pageFailure()!!
        }

    override suspend fun read(page: PageEntry): PageResult<ByteArray> =
        when (val result = session.read(page.entryIndex)) {
            is EntryResult.Data -> PageResult.Ready(result.bytes)
            else -> result.pageFailure()!!
        }

    override fun close() = session.close()
}

private class FolderPages(private val files: List<FolderFile>) : PageBytes {
    override suspend fun prefix(page: PageEntry, count: Int): PageResult<ByteArray> =
        readSafely { files[page.entryIndex].open().use { readBounded(it, count) } }

    override suspend fun read(page: PageEntry): PageResult<ByteArray> = readSafely { files[page.entryIndex].open().use(::readAll) }

    override fun close() = Unit
}

/** The one page of a single image, read from the [source] the book holds open. */
private class SinglePage(private val source: ByteSource) : PageBytes {
    override suspend fun prefix(page: PageEntry, count: Int): PageResult<ByteArray> = readSafely { readBounded(source, count) }

    override suspend fun read(page: PageEntry): PageResult<ByteArray> = readSafely { readAll(source) }

    override fun close() = source.close()
}

/**
 * [read] on [Dispatchers.IO], with what it throws as a page failure (docs/spec.md section 2.4). A broken SMB connection
 * can be retried once it comes back (section 6.5).
 */
private suspend fun readSafely(read: () -> PageResult<ByteArray>): PageResult<ByteArray> = withContext(Dispatchers.IO) {
    try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: ConnectionLostException) {
        connectionLost
    } catch (_: Exception) {
        readError
    }
}

private fun readBounded(source: ByteSource, count: Int): PageResult<ByteArray> {
    val buffer = ByteBuffer.allocate(minOf(source.size, count.toLong()).toInt())
    while (buffer.hasRemaining()) {
        if (source.read(buffer.position().toLong(), buffer) <= 0) break
    }
    return PageResult.Ready(buffer.array().copyOf(buffer.position()))
}

/** All of [source], as long as it fits the limit of an entry (docs/spec.md section 3.7). */
private fun readAll(source: ByteSource): PageResult<ByteArray> {
    if (source.size > ArchiveSession.MAX_ENTRY_BYTES) return tooLargeEntry(source.size)
    val buffer = ByteBuffer.allocate(source.size.toInt())
    while (buffer.hasRemaining()) {
        if (source.read(buffer.position().toLong(), buffer) < 0) return PageResult.Failed(FailureReason.Truncated, retryable = false)
    }
    return PageResult.Ready(buffer.array())
}
