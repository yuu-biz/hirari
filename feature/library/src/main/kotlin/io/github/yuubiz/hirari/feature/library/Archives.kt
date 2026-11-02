package io.github.yuubiz.hirari.feature.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.yuubiz.hirari.core.archive.ArchiveSession
import io.github.yuubiz.hirari.core.archive.EntryNames
import io.github.yuubiz.hirari.core.archive.EntryResult
import io.github.yuubiz.hirari.core.archive.OpenResult
import io.github.yuubiz.hirari.core.image.PageCandidate
import io.github.yuubiz.hirari.core.image.pageCandidate
import io.github.yuubiz.hirari.core.image.sniffImage
import io.github.yuubiz.hirari.core.image.sortedPages
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.NaturalOrder
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.entryBookId
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.findDocumentId
import io.github.yuubiz.hirari.core.source.openByteSource
import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import io.github.yuubiz.hirari.core.source.smb.SmbConnectException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** One entry of an archive as the archive list reads it (docs/spec.md section 4.7). */
class ArchiveItem(
    val entryIndex: Int,
    /** The name before a charset is applied (section 3.4). */
    val rawName: ByteArray,
    val size: Long?,
    val isDirectory: Boolean,
    /** Encrypted, or declared too large, so the reader shows it as an error (section 2.4). */
    val unreadable: Boolean,
)

/** An open archive for its list. Closing it closes the archive. */
interface ArchiveContents : Closeable {
    val entries: List<ArchiveItem>

    /** The archive's own document, which the text reader opens a text entry of; null in an SMB place. */
    val documentUri: UriString?

    /** The charset of the names: [override] when the user chose one, else the detected one, else [fallback]. */
    fun charset(override: Charset?, fallback: Charset): Charset

    /**
     * The charset the image reader decodes the names in. The reader does not apply the book's choice yet, so a tap
     * finds the page by the reader's own order.
     */
    fun readerCharset(): Charset

    fun name(item: ArchiveItem, charset: Charset): String

    /** Whether an entry whose name tells nothing is an image by its first bytes (section 3.5). */
    suspend fun isImage(item: ArchiveItem): Boolean
}

enum class ArchiveFailure { NotFound, NoPermission, Encrypted, Unreachable, Unreadable }

sealed interface ArchiveOpenResult {
    class Opened(val contents: ArchiveContents) : ArchiveOpenResult

    data class Failed(val reason: ArchiveFailure) : ArchiveOpenResult
}

/** Opens the archive at [path] of [place] for its list, off the main thread. */
fun interface ArchiveOpener {
    suspend fun open(place: LibraryLocation, path: String, documentId: String?, book: BookId): ArchiveOpenResult
}

/** A page of the list. [readerIndex] is its place in the image reader's order, which a tap opens it at. */
data class ArchivePage(val entryIndex: Int, val name: String, val size: Long?, val readerIndex: Int, val unreadable: Boolean)

/** A text entry, read and marked as a book of its own (docs/spec.md section 2.2). */
data class ArchiveText(val entryIndex: Int, val name: String, val size: Long?, val id: BookId)

data class ArchiveRows(val texts: List<ArchiveText>, val pages: List<ArchivePage>)

/**
 * The texts and the pages of [contents] with its names in [charset] (docs/spec.md sections 3.5 and 4.7). Pages are
 * in natural order of those names, so another charset can reorder them; each keeps the position the reader gives it.
 * Texts are the `.txt` entries in natural order.
 */
suspend fun ArchiveContents.rows(charset: Charset, book: BookId): ArchiveRows {
    val readerOrder = pagesIn(readerCharset()).associate { it.entryIndex to it.index }
    val pages = pagesIn(charset).map { page ->
        val item = entries[page.entryIndex]
        ArchivePage(page.entryIndex, page.name, item.size, readerOrder[page.entryIndex] ?: 0, item.unreadable)
    }
    val texts = entries
        .filter { !it.isDirectory && name(it, charset).lowercase(Locale.ROOT).endsWith(".txt") }
        .map { ArchiveText(it.entryIndex, name(it, charset), it.size, entryBookId(book, it.rawName)) }
        .sortedWith(compareBy(NaturalOrder) { it.name })
    return ArchiveRows(texts, pages)
}

private suspend fun ArchiveContents.pagesIn(charset: Charset): List<PageEntry> = sortedPages(
    entries.mapNotNull { item ->
        val name = name(item, charset)
        val page = PageEntry(-1, item.entryIndex, item.rawName, name, item.size, sniffed = null)
        when (pageCandidate(name, item.isDirectory)) {
            PageCandidate.Image -> page
            PageCandidate.Excluded -> null
            PageCandidate.Sniff -> page.takeIf { isImage(item) }
        }
    },
)

/** The archives of SAF places through [resolver], and of SMB places through [smb]. */
class DeviceArchives(private val resolver: ContentResolver, private val cacheDir: File, private val smb: SmbPlaces) : ArchiveOpener {
    override suspend fun open(place: LibraryLocation, path: String, documentId: String?, book: BookId): ArchiveOpenResult {
        val (source, uri) = try {
            when (place) {
                is LibraryLocation.SafTree -> {
                    if (!place.permissionHeld) return ArchiveOpenResult.Failed(ArchiveFailure.NoPermission)
                    val tree = Uri.parse(place.treeUri.value)
                    val id = documentId ?: withContext(Dispatchers.IO) { resolver.findDocumentId(tree, path) } ?: throw FileNotFoundException()
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    openOwned(Dispatchers.IO) { resolver.openByteSource(uri, File(cacheDir, "copy")) } to UriString(uri.toString())
                }
                is LibraryLocation.SmbShare -> smb.open(place, path) to null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ArchiveOpenResult.Failed(e.archiveFailure())
        }
        // The reader extracts a solid archive into the same directory (docs/spec.md section 3.3), so both share it.
        return when (val opened = ArchiveSession.open(source, File(cacheDir, "extract/${book.value}"))) {
            is OpenResult.Opened -> ArchiveOpenResult.Opened(SessionContents(opened.session, uri))
            OpenResult.Encrypted -> ArchiveOpenResult.Failed(ArchiveFailure.Encrypted)
            is OpenResult.Failed -> ArchiveOpenResult.Failed(ArchiveFailure.Unreadable)
        }
    }

    private fun Exception.archiveFailure() = when (this) {
        is FileNotFoundException, is IllegalArgumentException -> ArchiveFailure.NotFound
        is SecurityException -> ArchiveFailure.NoPermission
        is SmbConnectException, is ConnectionLostException -> ArchiveFailure.Unreachable
        else -> ArchiveFailure.Unreadable
    }
}

private class SessionContents(private val session: ArchiveSession, override val documentUri: UriString?) : ArchiveContents {
    private val sniffed = ConcurrentHashMap<Int, Boolean>()

    override val entries: List<ArchiveItem> = session.entries.map {
        ArchiveItem(it.entryIndex, it.rawName, it.declaredSize, it.isDirectory, it.encrypted || (it.declaredSize ?: 0) > ArchiveSession.MAX_ENTRY_BYTES)
    }

    override fun charset(override: Charset?, fallback: Charset): Charset = session.nameCharset(override, fallback)

    override fun readerCharset(): Charset = session.nameCharset()

    override fun name(item: ArchiveItem, charset: Charset): String = session.name(session.entries[item.entryIndex], charset)

    override suspend fun isImage(item: ArchiveItem): Boolean = sniffed[item.entryIndex] ?: run {
        val bytes = (session.read(item.entryIndex) as? EntryResult.Data)?.bytes
        (bytes != null && sniffImage(bytes) != null).also { sniffed[item.entryIndex] = it }
    }

    override fun close() = session.close()
}

/**
 * [open] on [context]. When the caller is cancelled after [open] made its handle, `withContext` drops the handle; it
 * is closed here before the cancellation goes on, so a file or a remote handle is never left open.
 */
internal suspend fun <T : Closeable> openOwned(context: CoroutineContext, open: () -> T): T {
    val made = AtomicReference<T?>()
    try {
        return withContext(context) { open().also(made::set) }
    } catch (e: CancellationException) {
        made.get()?.close()
        throw e
    }
}

/** The charsets a user may choose for the names (docs/spec.md section 3.4), with their labels. */
val NAME_CHARSETS: List<Pair<Charset, String>> = EntryNames.choices.map { charset ->
    charset to when (charset) {
        EntryNames.CP932 -> "Shift_JIS (CP932)"
        else -> charset.name()
    }
}
