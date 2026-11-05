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
import io.github.yuubiz.hirari.core.data.SettingsRepository
import io.github.yuubiz.hirari.core.model.BOOK_ID_PREFIX_BYTES
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.TextEntry
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
import io.github.yuubiz.hirari.core.source.smb.PasswordUnreadableException
import io.github.yuubiz.hirari.core.text.GlyphMetrics
import io.github.yuubiz.hirari.core.text.PaintGlyphMetrics
import io.github.yuubiz.hirari.core.text.resolvePicturePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
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

    /** The entry at [entryIndex], in the stored order, of [archive], whose id is [archiveId]. */
    data class ArchiveEntry(val archive: EntryArchive, val entryIndex: Int, val archiveId: BookId) : TextBookRef
}

/** The archive a text entry is read from. */
sealed interface EntryArchive {
    /** The archive's document, of a SAF place or picked outside a place. */
    data class Document(val uri: Uri) : EntryArchive

    /** The archive at [path] of a device or SMB place. */
    data class InLocation(val location: LocationId, val path: String) : EntryArchive
}

/** The files of the SMB places, through the connection the library and the image reader share (docs/spec.md section 6.5). */
fun interface SmbFiles {
    /** The file at [path] of [place]. Throws when the server or the file cannot be reached. */
    suspend fun open(place: LibraryLocation.SmbShare, path: String): ByteSource
}

/**
 * How the names of an archive are decoded (docs/spec.md section 3.4): in [chosen] when the user chose a charset for
 * the book, else in the detected one, else in [undetermined], the setting `fallbackCharset`. The archive list decodes
 * them the same way, so a text entry has the name the list shows.
 */
internal data class EntryCharsets(val chosen: Charset?, val undetermined: Charset)

/** The [EntryCharsets] of the archive [archiveId] from its overrides and the settings. */
internal suspend fun entryCharsets(archiveId: BookId, books: BookRepository, settings: SettingsRepository) = EntryCharsets(
    books.overrides(archiveId).first().nameCharset,
    Charset.forName(settings.settings.first().fallbackCharset.charsetName),
)

/** What the openers read from. [charsets] gives the [EntryCharsets] of an archive by its id. */
internal class TextSources(
    val resolver: ContentResolver,
    val locations: LocationRepository,
    val smb: SmbFiles,
    val copyDir: File,
    val pictureBudget: Int = pictureBudgetBytes(),
    val charsets: suspend (archiveId: BookId) -> EntryCharsets,
)

/**
 * The opener of [ref]. Every step blocks on Binder, the disk or the network, so all of it runs on `Dispatchers.IO`
 * (section 6.4).
 */
internal fun opener(ref: TextBookRef, sources: TextSources): TextOpener = TextOpener {
    openOn(Dispatchers.IO) {
        guarded {
            when (ref) {
                is TextBookRef.Document -> openDocument(sources.resolver, ref.uri, id = null, BookSource.External(UriString(ref.uri.toString())))
                is TextBookRef.InLocation -> openInLocation(sources, ref)
                is TextBookRef.ArchiveEntry -> {
                    val charsets = sources.charsets(ref.archiveId)
                    openEntry(sources.archive(ref.archive), ref.archiveId, ref.entryIndex, charsets, ref.archive, sources.pictureBudget)
                }
            }
        }
    }
}

/** A text of a place, by its path inside the place, as the image reader finds a book there. */
private suspend fun openInLocation(sources: TextSources, ref: TextBookRef.InLocation): TextOpenResult {
    val path = normalizePath(ref.path)
    val source = BookSource.InLocation(ref.location, path)
    val pictures = PictureReader { requested ->
        val resolved = resolvePicturePath("", requested) ?: return@PictureReader null
        withContext(Dispatchers.IO) {
            when (val place = sources.locations.get(ref.location)) {
                null -> null
                is LibraryLocation.SafTree -> {
                    if (!place.permissionHeld) return@withContext null
                    val tree = Uri.parse(place.treeUri.value)
                    val document = sources.resolver.locateDocument(tree, resolved) ?: return@withContext null
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, document)
                    val size = sources.resolver.documentInfo(uri)?.size
                    if (size != null && size > sources.pictureBudget) return@withContext null
                    sources.resolver.openInputStream(uri)?.use { readCapped(it, sources.pictureBudget) }
                }
                is LibraryLocation.SmbShare -> sources.smb.open(place, resolved).use { readPicture(it, sources.pictureBudget) }
            }
        }
    }
    return when (val place = sources.locations.get(ref.location)) {
        null -> TextOpenResult.Failed(TextFailure.NotFound)
        is LibraryLocation.SafTree -> {
            if (!place.permissionHeld) return TextOpenResult.Failed(TextFailure.NoPermission)
            val tree = Uri.parse(place.treeUri.value)
            val documentId = ref.documentId ?: sources.resolver.locateDocument(tree, path) ?: return TextOpenResult.Failed(TextFailure.NotFound)
            openDocument(sources.resolver, DocumentsContract.buildDocumentUriUsingTree(tree, documentId), locationBookId(place, path), source, path, pictures)
        }
        is LibraryLocation.SmbShare -> sources.smb.open(place, path).use { openServerFile(it, locationBookId(place, path), source, pictures) }
    }
}

private suspend fun TextSources.archive(archive: EntryArchive): ByteSource = when (archive) {
    is EntryArchive.Document -> resolver.openByteSource(archive.uri, copyDir)
    is EntryArchive.InLocation -> {
        when (val place = locations.get(archive.location)) {
            null -> throw FileNotFoundException(archive.path)
            is LibraryLocation.SmbShare -> smb.open(place, normalizePath(archive.path))
            is LibraryLocation.SafTree -> {
                if (!place.permissionHeld) throw SecurityException("Place permission lost")
                val tree = Uri.parse(place.treeUri.value)
                val document = resolver.locateDocument(tree, normalizePath(archive.path)) ?: throw FileNotFoundException(archive.path)
                resolver.openByteSource(DocumentsContract.buildDocumentUriUsingTree(tree, document), copyDir)
            }
        }
    }
}

/**
 * A text of an SMB place: its bytes and its entry for the index. The server is asked for nothing but the file, so the
 * title is the last name of the path and the modification time stays unknown, as for the image reader's books.
 */
private fun openServerFile(file: ByteSource, id: BookId, source: BookSource.InLocation, pictures: PictureReader): TextOpenResult {
    if (file.size > MAX_TEXT_BYTES) return TextOpenResult.Failed(TextFailure.TooLarge)
    val buffer = ByteBuffer.allocate(file.size.toInt())
    while (buffer.hasRemaining() && file.read(buffer.position().toLong(), buffer) >= 0) Unit
    val bytes = buffer.array().copyOf(buffer.position())
    val title = source.path.substringAfterLast('/')
    val book = Book(id, source, BookFormat.Text(charset = null), title, byteSize = file.size, modified = Instant.EPOCH, pageCount = null)
    return TextOpenResult.Opened(OpenedText(id, title, source.path, book, pictures) {}, bytes)
}

/**
 * The document's bytes, its id, and its entry for the index. Without [id] the document is a single one, whose id
 * comes from its first bytes (docs/spec.md section 2.2).
 */
private suspend fun openDocument(resolver: ContentResolver, uri: Uri, id: BookId?, source: BookSource, path: String = "", pictures: PictureReader? = null): TextOpenResult {
    val info = try {
        resolver.documentInfo(uri)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    val bytes = (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())).use { readCapped(it, MAX_TEXT_BYTES) }
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
    return TextOpenResult.Opened(OpenedText(bookId, title, path, book, pictures) {}, bytes)
}

/**
 * The bytes of entry [entryIndex] of the archive in [source], which the text owns from here on, and a reader of its
 * 挿絵 from the same archive, which stays open with the text. The index keeps [archive] and the entry so home
 * bookmarks reopen the text (docs/spec.md section 4.6). The names are decoded in [charsets].
 */
internal suspend fun openEntry(source: ByteSource, archiveId: BookId, entryIndex: Int, charsets: EntryCharsets, archive: EntryArchive, pictureBudget: Int = pictureBudgetBytes()): TextOpenResult = guarded {
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
        val charset = session.nameCharset(charsets.chosen, charsets.undetermined)
        val name = session.name(entry, charset)
        val byPath = session.entries.filterNot { it.isDirectory }.associateBy { normalizePath(session.name(it, charset)) }
        val pictures = PictureReader { path ->
            val picture = byPath[normalizePath(path)] ?: return@PictureReader null
            withContext(Dispatchers.IO) {
                (session.read(picture.entryIndex, pictureBudget) as? EntryResult.Data)?.bytes
            }
        }
        val id: BookId = entryBookId(archiveId, entry.rawName)
        val bookSource = when (archive) {
            is EntryArchive.Document -> BookSource.External(UriString(archive.uri.toString()))
            is EntryArchive.InLocation -> BookSource.InLocation(archive.location, normalizePath(archive.path))
        }
        val book = Book(
            id, bookSource, BookFormat.Text(charset = null), name.substringAfterLast('/'),
            bytes.size.toLong(), Instant.EPOCH, pageCount = null,
            textEntry = TextEntry(archiveId, entry.entryIndex),
        )
        TextOpenResult.Opened(OpenedText(id, book.title, normalizePath(name), book, pictures, session::close), bytes)
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
            is SecurityException, is PasswordUnreadableException -> TextFailure.NoPermission
            is NoRoomForCopyException -> TextFailure.NoRoomForCopy
            else -> TextFailure.ReadError
        },
    )
}

/** The whole stream, or null when it is longer than [limit], including streams without a declared size. */
private fun readCapped(input: InputStream, limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return out.toByteArray()
        if (out.size() + read > limit) return null
        out.write(buffer, 0, read)
    }
}

/** Check the server's declared length before allocating or making the first read. */
internal fun readPicture(file: ByteSource, budget: Int): ByteArray? {
    if (file.size < 0 || file.size > budget) return null
    val buffer = ByteBuffer.allocate(file.size.toInt())
    while (buffer.hasRemaining() && file.read(buffer.position().toLong(), buffer) >= 0) Unit
    return if (buffer.hasRemaining()) null else buffer.array()
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
