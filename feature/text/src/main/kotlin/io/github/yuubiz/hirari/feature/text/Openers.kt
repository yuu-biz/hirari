package io.github.yuubiz.hirari.feature.text

import android.content.ContentResolver
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import io.github.yuubiz.hirari.core.archive.ArchiveSession
import io.github.yuubiz.hirari.core.archive.EntryResult
import io.github.yuubiz.hirari.core.archive.OpenResult
import io.github.yuubiz.hirari.core.data.BookRepository
import io.github.yuubiz.hirari.core.data.FontStore
import io.github.yuubiz.hirari.core.data.HistoryRepository
import io.github.yuubiz.hirari.core.data.LocationRepository
import io.github.yuubiz.hirari.core.model.BOOK_ID_PREFIX_BYTES
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.TextTypeface
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.documentBookId
import io.github.yuubiz.hirari.core.model.entryBookId
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.model.normalizePath
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.NoRoomForCopyException
import io.github.yuubiz.hirari.core.source.documentInfo
import io.github.yuubiz.hirari.core.source.locateDocument
import io.github.yuubiz.hirari.core.source.openByteSource
import io.github.yuubiz.hirari.core.text.GlyphMetrics
import io.github.yuubiz.hirari.core.text.PaintGlyphMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * A text book (docs/spec.md section 4.9), with what its id is made of (section 2.2), so the file list, the reader,
 * the history and a shortcut keep its progress under one id.
 */
sealed interface TextBookRef {
    /** A document picked or handed over outside a place; its id comes from its first bytes. */
    data class Document(val uri: Uri) : TextBookRef

    /** The text at [path] of a place. [documentId] is the provider's id when the caller knows it. */
    data class InLocation(val location: LocationId, val path: String, val documentId: String? = null) : TextBookRef

    /** The entry at [entryIndex], in the stored order, of the archive document at [archive], whose id is [archiveId]. */
    data class ArchiveEntry(val archive: Uri, val entryIndex: Int, val archiveId: BookId) : TextBookRef
}

/** The opener of [ref]. Every step blocks on Binder or the disk, so all of it runs on `Dispatchers.IO` (section 6.4). */
internal fun opener(ref: TextBookRef, resolver: ContentResolver, locations: LocationRepository, copyDir: File): TextOpener = when (ref) {
    is TextBookRef.Document -> TextOpener {
        openOn(Dispatchers.IO) { guarded { openDocument(resolver, ref.uri, id = null, BookSource.External(UriString(ref.uri.toString()))) } }
    }
    is TextBookRef.InLocation -> TextOpener { openOn(Dispatchers.IO) { guarded { openInLocation(resolver, locations, ref) } } }
    is TextBookRef.ArchiveEntry -> TextOpener {
        openOn(Dispatchers.IO) { guarded { openEntry(resolver.openByteSource(ref.archive, copyDir), ref.archiveId, ref.entryIndex) } }
    }
}

/** A text of a SAF place, by its path inside the place, as the image reader finds a book there. */
private suspend fun openInLocation(resolver: ContentResolver, locations: LocationRepository, ref: TextBookRef.InLocation): TextOpenResult {
    val path = normalizePath(ref.path)
    val place = locations.get(ref.location) as? LibraryLocation.SafTree ?: return TextOpenResult.Failed(TextFailure.NotFound)
    if (!place.permissionHeld) return TextOpenResult.Failed(TextFailure.NoPermission)
    val tree = Uri.parse(place.treeUri.value)
    val documentId = ref.documentId ?: resolver.locateDocument(tree, path) ?: return TextOpenResult.Failed(TextFailure.NotFound)
    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    return openDocument(resolver, uri, locationBookId(place, path), BookSource.InLocation(ref.location, path))
}

/**
 * The document's bytes, its id, and its entry for the index. Without [id] the document is a single one, whose id
 * comes from its first bytes (docs/spec.md section 2.2).
 */
private suspend fun openDocument(resolver: ContentResolver, uri: Uri, id: BookId?, source: BookSource): TextOpenResult {
    val info = try {
        resolver.documentInfo(uri)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    val bytes = (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())).use(::readCapped)
        ?: return TextOpenResult.Failed(TextFailure.TooLarge)
    val bookId = id ?: documentBookId(bytes.copyOf(minOf(bytes.size, BOOK_ID_PREFIX_BYTES)), bytes.size.toLong())
    val title = info?.name ?: (source as? BookSource.InLocation)?.path?.substringAfterLast('/') ?: uri.lastPathSegment ?: uri.toString()
    val book = Book(
        id = bookId,
        source = source,
        format = BookFormat.Text(charset = null),
        title = title,
        byteSize = info?.size ?: bytes.size.toLong(),
        modified = info?.lastModifiedMs?.let(Instant::ofEpochMilli) ?: Instant.EPOCH,
        pageCount = null,
    )
    return TextOpenResult.Opened(OpenedText(bookId, title, path = "", book, pictures = null) {}, bytes)
}

/**
 * The bytes of entry [entryIndex] of the archive in [source], which the text owns from here on, and a reader of its
 * 挿絵 from the same archive, which stays open with the text. The entry has no form in the library index
 * (`BookSource` names no entry), so it gets none; its progress and bookmarks still keep.
 */
internal suspend fun openEntry(source: ByteSource, archiveId: BookId, entryIndex: Int): TextOpenResult = guarded {
    val session = when (val opened = ArchiveSession.open(source)) {
        is OpenResult.Failed -> return@guarded TextOpenResult.Failed(TextFailure.ReadError)
        OpenResult.Encrypted -> return@guarded TextOpenResult.Failed(TextFailure.Encrypted)
        is OpenResult.Opened -> opened.session
    }
    try {
        val entry = session.entries.getOrNull(entryIndex)
            ?: return@guarded TextOpenResult.Failed(TextFailure.NotFound).also { session.close() }
        val bytes = when (val read = session.read(entry.entryIndex, MAX_TEXT_BYTES)) {
            is EntryResult.Data -> read.bytes
            is EntryResult.TooLarge -> return@guarded TextOpenResult.Failed(TextFailure.TooLarge).also { session.close() }
            EntryResult.Encrypted -> return@guarded TextOpenResult.Failed(TextFailure.Encrypted).also { session.close() }
            is EntryResult.Failed, is EntryResult.UnsupportedCompression ->
                return@guarded TextOpenResult.Failed(TextFailure.ReadError).also { session.close() }
        }
        val charset = session.nameCharset()
        val name = session.name(entry, charset)
        val byPath = session.entries.filterNot { it.isDirectory }.associateBy { normalizePath(session.name(it, charset)) }
        val pictures = PictureReader { path ->
            val picture = byPath[normalizePath(path)] ?: return@PictureReader null
            (session.read(picture.entryIndex) as? EntryResult.Data)?.bytes
        }
        val id: BookId = entryBookId(archiveId, entry.rawName)
        TextOpenResult.Opened(OpenedText(id, name.substringAfterLast('/'), normalizePath(name), book = null, pictures, session::close), bytes)
    } catch (e: Throwable) {
        session.close()
        throw e
    }
}

/** Runs [open], turning every failure but cancellation into a [TextFailure] (docs/spec.md section 6.1). */
private suspend fun guarded(open: suspend () -> TextOpenResult): TextOpenResult = try {
    open()
} catch (e: CancellationException) {
    throw e
} catch (_: OutOfMemoryError) {
    TextOpenResult.Failed(TextFailure.TooLarge)
} catch (e: Exception) {
    TextOpenResult.Failed(
        when (e) {
            is FileNotFoundException, is IllegalArgumentException -> TextFailure.NotFound
            is SecurityException -> TextFailure.NoPermission
            is NoRoomForCopyException -> TextFailure.NoRoomForCopy
            else -> TextFailure.ReadError
        },
    )
}

/** The whole stream, or null when it is longer than [MAX_TEXT_BYTES]. */
private fun readCapped(input: InputStream): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return out.toByteArray()
        if (out.size() + read > MAX_TEXT_BYTES) return null
        out.write(buffer, 0, read)
    }
}

/**
 * The system and imported typefaces, each loaded once, off the main thread: an imported one is a file. The reader
 * draws with [typeface] what it laid out with [metrics].
 */
internal class TypefaceCache(private val fonts: FontStore) : TextFonts {
    private val loaded = ConcurrentHashMap<TextTypeface, Typeface>()

    override suspend fun available(typeface: TextTypeface): TextTypeface = when (typeface) {
        TextTypeface.Mincho, TextTypeface.Gothic -> typeface
        is TextTypeface.Imported -> if (load(typeface) != null) typeface else TextTypeface.Mincho
    }

    override suspend fun metrics(typeface: TextTypeface): GlyphMetrics =
        PaintGlyphMetrics(load(typeface) ?: Typeface.SERIF)

    /** The typeface [available] or [metrics] loaded, or the system serif before then. */
    fun typeface(typeface: TextTypeface): Typeface = loaded[typeface] ?: Typeface.SERIF

    private suspend fun load(typeface: TextTypeface): Typeface? = loaded[typeface] ?: when (typeface) {
        TextTypeface.Mincho -> Typeface.SERIF
        TextTypeface.Gothic -> Typeface.SANS_SERIF
        is TextTypeface.Imported -> withContext(Dispatchers.IO) {
            fonts.file(typeface.fileName)?.let { file -> runCatching { Typeface.createFromFile(file) }.getOrNull() }
        }
    }?.also { loaded[typeface] = it }
}

/**
 * The index entry when the text opens and its place in the history when the reader stops or closes, stamped with
 * the time it opened (docs/spec.md sections 2.2, 2.8 and 4.6), as the image reader writes them. A text with no index
 * entry gets no history either, since the history lists books of the index. Failed writes are dropped.
 */
internal class RepositoryTextHistory(
    private val books: BookRepository,
    private val history: HistoryRepository,
    private val scope: CoroutineScope,
) : TextHistory {
    private val stopped = AtomicBoolean(false)
    private var write: (suspend () -> Unit)? = null
    private var indexed: Job? = null

    override fun opened(id: BookId, book: Book?, openedAt: Instant, limit: Int) {
        book ?: return
        indexed = scope.launch { quietly { books.record(book) } }
        write = { history.record(id, openedAt, limit) }
    }

    override fun stop() {
        val write = write ?: return
        if (!stopped.compareAndSet(false, true)) return
        scope.launch {
            indexed?.join()
            quietly(write)
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
