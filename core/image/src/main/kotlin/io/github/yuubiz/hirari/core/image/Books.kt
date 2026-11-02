package io.github.yuubiz.hirari.core.image

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.yuubiz.hirari.core.archive.ArchiveFailure
import io.github.yuubiz.hirari.core.archive.ArchiveSession
import io.github.yuubiz.hirari.core.archive.EntryResult
import io.github.yuubiz.hirari.core.archive.OpenResult
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.ImageType
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.NoRoomForCopyException
import io.github.yuubiz.hirari.core.source.listChildren
import io.github.yuubiz.hirari.core.source.openByteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
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

    /** A password protects the whole book, such as a PDF with a user password (docs/spec.md section 3.8). */
    Encrypted,
    Corrupt,
    Truncated,

    /** The archive's headers are encrypted (docs/spec.md section 3.8). */
    Encrypted,
    ReadError,
}

/** One image file of a folder book. [open] blocks, and is called on [Dispatchers.IO]. */
class FolderFile(val name: String, val size: Long?, val open: () -> ByteSource)

/** Opens books for the reader. Every function returns a [BookResult] and throws nothing but cancellation. */
object Books {
    /** Opens the ZIP in [source], which the book owns from here on. */
    suspend fun openArchive(source: ByteSource, decoding: ImageDecoding): BookResult {
        val session = when (val opened = ArchiveSession.open(source)) {
            is OpenResult.Failed -> return BookResult.Failed(opened.reason.bookFailure())
            OpenResult.Encrypted -> return BookResult.Failed(BookFailure.Encrypted)
            is OpenResult.Opened -> opened.session
        }
        return try {
            val charset = session.nameCharset()
            val pages = session.entries.mapNotNull { entry ->
                val name = session.name(entry, charset)
                val page = PageEntry(-1, entry.entryIndex, entry.rawName, name, entry.declaredSize, sniffed = null)
                when (pageCandidate(name, entry.isDirectory)) {
                    PageCandidate.Image -> page
                    PageCandidate.Excluded -> null
                    PageCandidate.Sniff -> (session.read(entry.entryIndex) as? EntryResult.Data)
                        ?.let { sniffImage(it.bytes) }
                        ?.let { page.copy(sniffed = it) }
                }
            }
            BookResult.Opened(OpenBook(BookContent.Images(ArchivePages(session)), sortedPages(pages), session.indexError?.pageFailure()?.reason, decoding))
        } catch (_: OutOfMemoryError) {
            session.close()
            BookResult.Failed(BookFailure.ReadError)
        } catch (e: Throwable) {
            session.close()
            throw e
        }
    }

    /** Opens the image files of a folder (docs/spec.md section 3.10). [files] are the files directly inside it. */
    suspend fun openFolder(files: List<FolderFile>, decoding: ImageDecoding): BookResult {
        val pages = withContext(Dispatchers.IO) {
            files.mapIndexedNotNull { i, file ->
                val page = PageEntry(-1, i, file.name.toByteArray(Charsets.UTF_8), file.name, file.size, sniffed = null)
                when (pageCandidate(file.name, isDirectory = false)) {
                    PageCandidate.Image -> page
                    PageCandidate.Excluded -> null
                    PageCandidate.Sniff -> sniffPrefix(file)?.let { page.copy(sniffed = it) }
                }
            }
        }
        return BookResult.Opened(OpenBook(BookContent.Images(FolderPages(files)), sortedPages(pages), indexError = null, decoding))
    }

    /**
     * Opens the book in [source], which the book owns from here on: a PDF when `%PDF-` starts within its first
     * 1024 bytes (docs/spec.md section 3.2), and otherwise an archive.
     */
    suspend fun openBook(source: ByteSource, decoding: ImageDecoding): BookResult {
        val prefix = when (val read = safely { readPrefix(source, PDF_SNIFF_BYTES) }) {
            is Safely.Done -> read.value
            is Safely.Failed -> {
                source.close()
                return BookResult.Failed(read.reason)
            }
        }
        return if (isPdf(prefix)) openPdf(source, decoding) else openArchive(source, decoding)
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
        val pages = document.shapes.indices.map { i ->
            val name = "${i + 1}"
            PageEntry(i, i, name.toByteArray(Charsets.US_ASCII), name, declaredSize = null, sniffed = ImageType.PdfPage)
        }
        return BookResult.Opened(OpenBook(BookContent.Pdf(document), pages, indexError = null, decoding))
    }

    /** Opens the ZIP or PDF document at [uri]. A provider that cannot seek gets the document copied into [copyDir]. */
    suspend fun openDocument(resolver: ContentResolver, uri: Uri, copyDir: File, decoding: ImageDecoding): BookResult {
        val source = when (val opened = safely(release = ByteSource::close) { resolver.openByteSource(uri, copyDir) }) {
            is Safely.Done -> opened.value
            is Safely.Failed -> return BookResult.Failed(opened.reason)
        }
        return openBook(source, decoding)
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

    private fun sniffPrefix(file: FolderFile) = try {
        file.open().use { source -> sniffImage(readPrefix(source, SNIFF_BYTES)) }
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

private fun ArchiveFailure.bookFailure(): BookFailure = when (this) {
    ArchiveFailure.Corrupt -> BookFailure.Corrupt
    ArchiveFailure.Truncated -> BookFailure.Truncated
    ArchiveFailure.ReadError -> BookFailure.ReadError
}

private class ArchivePages(private val session: ArchiveSession) : PageBytes {
    override suspend fun read(page: PageEntry): PageResult<ByteArray> =
        when (val result = session.read(page.entryIndex)) {
            is EntryResult.Data -> PageResult.Ready(result.bytes)
            else -> result.pageFailure()!!
        }

    override fun close() = session.close()
}

private class FolderPages(private val files: List<FolderFile>) : PageBytes {
    override suspend fun read(page: PageEntry): PageResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            files[page.entryIndex].open().use(::readAll)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            readError
        }
    }

    private fun readAll(source: ByteSource): PageResult<ByteArray> {
        if (source.size > ArchiveSession.MAX_ENTRY_BYTES) return tooLargeEntry(source.size)
        val buffer = ByteBuffer.allocate(source.size.toInt())
        while (buffer.hasRemaining()) {
            if (source.read(buffer.position().toLong(), buffer) < 0) return PageResult.Failed(FailureReason.Truncated, retryable = false)
        }
        return PageResult.Ready(buffer.array())
    }

    override fun close() = Unit
}
