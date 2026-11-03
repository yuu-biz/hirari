package io.github.yuubiz.hirari.core.data

import androidx.room3.withWriteTransaction
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.Bookmark
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.HistoryEntry
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.NaturalOrder
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.normalizePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant

/** A book of the index with what the library shows on it (docs/spec.md sections 4.2 and 4.3). */
data class LibraryBook(
    val book: Book,
    /** The `entryIndex` of the page the user chose as the cover, or `null` for the first page. */
    val coverEntry: Int?,
    val progress: BookProgress?,
    /** The part of that page the user chose as the cover, or `null` for the whole page (docs/spec.md section 4.4). */
    val coverCrop: CoverCrop? = null,
)

/** The index of opened books, their covers and their overrides. Safe to call from the main thread. */
interface BookRepository {
    /** Call when a book opens. Keeps the page count when [book] does not know it, and the chosen cover. */
    suspend fun record(book: Book)

    suspend fun get(id: BookId): LibraryBook?

    /** The indexed books directly in [folder] of [location], for the read state of a file list. */
    fun inFolder(location: LocationId, folder: String): Flow<List<LibraryBook>>

    /** 続きを読む: books being read, last read first (docs/spec.md section 4.2). */
    fun continueReading(limit: Int = 10): Flow<List<LibraryBook>>

    /**
     * Stores the page [entry] as the cover, with the part [crop] of it, or the whole page when `null`. An [entry] of
     * `null` goes back to the first page in natural order, which has no range (docs/spec.md section 4.4).
     */
    suspend fun setCover(id: BookId, entry: Int?, crop: CoverCrop? = null)

    fun overrides(id: BookId): Flow<BookOverrides>

    /**
     * Stores [change] of the stored overrides of book [id], read and written in one transaction, so writers that
     * change different fields keep each other's change (docs/spec.md section 2.9).
     */
    suspend fun updateOverrides(id: BookId, change: (BookOverrides) -> BookOverrides)
}

internal class RoomBookRepository(private val database: HirariDatabase) : BookRepository {
    private val books = database.books()
    private val overrides = database.bookOverrides()

    override suspend fun record(book: Book) = books.record(book.toRow(coverEntry = null))

    override suspend fun get(id: BookId): LibraryBook? = books.get(id.value)?.let { row ->
        LibraryBook(row.toModel(), row.coverEntry, database.bookProgress().get(id.value)?.toModel(), row.coverCropOrNull())
    }

    override fun inFolder(location: LocationId, folder: String): Flow<List<LibraryBook>> =
        books.observeFolder(location.value, normalizePath(folder)).map { rows -> rows.map { it.toModel() } }

    override fun continueReading(limit: Int): Flow<List<LibraryBook>> =
        books.observeReading(limit).map { rows -> rows.map { it.toModel() } }

    override suspend fun setCover(id: BookId, entry: Int?, crop: CoverCrop?) =
        books.setCover(id.value, entry, crop?.takeIf { entry != null }?.encode())

    override fun overrides(id: BookId): Flow<BookOverrides> =
        overrides.observe(id.value).map { it?.overrides?.toBookOverrides() ?: BookOverrides() }

    override suspend fun updateOverrides(id: BookId, change: (BookOverrides) -> BookOverrides) = database.withWriteTransaction {
        val next = change(overrides.get(id.value)?.overrides?.toBookOverrides() ?: BookOverrides())
        if (next == BookOverrides()) {
            overrides.delete(id.value)
        } else {
            overrides.upsert(listOf(BookOverridesRow(id.value, next.toJson().toString())))
        }
    }
}

private fun BookWithProgress.toModel() = LibraryBook(book.toModel(), book.coverEntry, progress?.toModel(), book.coverCropOrNull())

/** The range that was stored, or none when the text does not read (the page is then shown whole). */
private fun BookRow.coverCropOrNull(): CoverCrop? = coverCrop?.let(CoverCrop::decode)

data class HistoryItem(
    val entry: HistoryEntry,
    /** `null` when the index no longer has the book, as after importing a settings file. */
    val book: Book?,
    val location: LibraryLocation?,
    val progress: BookProgress?,
    /** The `entryIndex` of the page the user chose as the cover of [book], or `null` for the first page. */
    val coverEntry: Int? = null,
    /** The part of that page the user chose, or `null` for the whole page. */
    val coverCrop: CoverCrop? = null,
)

/** The history tab (docs/spec.md sections 2.8 and 4.6). Safe to call from the main thread. */
interface HistoryRepository {
    fun observe(sort: HistorySort): Flow<List<HistoryItem>>

    /** Moves [book] to the newest place and keeps the newest [limit] books, the `historyLimit` setting. */
    suspend fun record(book: BookId, openedAt: Instant, limit: Int)

    suspend fun remove(book: BookId)

    suspend fun clear()
}

internal class RoomHistoryRepository(private val database: HirariDatabase) : HistoryRepository {
    private val dao = database.history()

    override fun observe(sort: HistorySort): Flow<List<HistoryItem>> =
        combine(dao.observe(), database.locations().observeAll()) { rows, locationRows ->
            val locations = locationRows.associate { it.id to it.toModel() }
            val items = rows.map { row ->
                val book = row.book?.toModel()
                HistoryItem(
                    entry = HistoryEntry(BookId(row.history.bookId), Instant.ofEpochMilli(row.history.openedAt)),
                    book = book,
                    location = (book?.source as? BookSource.InLocation)?.let { locations[it.location.value] },
                    progress = row.progress?.toModel(),
                    coverEntry = row.book?.coverEntry,
                    coverCrop = row.book?.coverCropOrNull(),
                )
            }
            when (sort) {
                HistorySort.Newest -> items
                HistorySort.Oldest -> items.reversed()
                HistorySort.PathAscending -> items.sortedWith(compareBy(NaturalOrder) { it.pathKey })
                HistorySort.PathDescending -> items.sortedWith(compareBy(NaturalOrder) { it.pathKey }).reversed()
            }
        }

    override suspend fun record(book: BookId, openedAt: Instant, limit: Int) =
        dao.record(HistoryRow(book.value, openedAt.toEpochMilli()), limit.coerceAtLeast(0))

    override suspend fun remove(book: BookId) = dao.delete(book.value)

    override suspend fun clear() = dao.deleteAll()
}

private val HistoryItem.pathKey: String get() = pathKey(book, location, entry.book)

/** Books from other apps sort after the places, and books the index lost after those, by id. */
private fun pathKey(book: Book?, location: LibraryLocation?, id: BookId): String = when (val source = book?.source) {
    is BookSource.InLocation -> "${location?.displayName ?: "￿"}/${source.path}"
    is BookSource.External -> "￿${book.title}"
    null -> "￿￿${id.value}"
}

data class BookmarkGroup(val book: BookId, val info: Book?, val location: LibraryLocation?, val bookmarks: List<Bookmark>)

/** Which bookmarks a bulk delete removes (docs/spec.md section 4.6). */
sealed interface BookmarkScope {
    data object All : BookmarkScope

    /** The books the caller found missing. */
    data class Books(val books: Collection<BookId>) : BookmarkScope

    /** Books in [folder] of [location] and in the folders under it. */
    data class UnderFolder(val location: LocationId, val folder: String) : BookmarkScope

    data class InLocation(val location: LocationId) : BookmarkScope

    /** Books that are not on an SMB server: SAF places and books opened from other apps. */
    data object OnDevice : BookmarkScope
}

/** The bookmarks tab and the reader's bookmark list (docs/spec.md sections 2.8 and 4.6). Safe on the main thread. */
interface BookmarkRepository {
    /**
     * Bookmarks grouped by book. Path and name orders sort the groups by the book's path or title and the
     * bookmarks inside by position; date orders sort both by when the bookmark was made.
     */
    fun observe(sort: BookmarkSort): Flow<List<BookmarkGroup>>

    fun inBook(book: BookId): Flow<List<Bookmark>>

    /** A `null` [label] takes the default of docs/spec.md section 2.8. */
    suspend fun add(book: BookId, position: ReadingPosition, label: String?, createdAt: Instant): Bookmark

    suspend fun rename(id: Long, label: String)

    suspend fun delete(ids: Collection<Long>)

    suspend fun delete(scope: BookmarkScope)
}

/** `p. 12` for an image book and `12%` for a text, from the first page or character of the view. */
fun defaultBookmarkLabel(position: ReadingPosition): String = when (position) {
    is ReadingPosition.Image -> "p. ${position.viewStart + 1}"
    is ReadingPosition.Text -> "${if (position.totalChars > 0) position.charOffset * 100 / position.totalChars else 0}%"
}

internal class RoomBookmarkRepository(private val database: HirariDatabase) : BookmarkRepository {
    private val dao = database.bookmarks()

    override fun observe(sort: BookmarkSort): Flow<List<BookmarkGroup>> =
        combine(dao.observe(), database.locations().observeAll()) { rows, locationRows ->
            val locations = locationRows.associate { it.id to it.toModel() }
            val groups = rows.groupBy { it.bookmark.bookId }.map { (id, marks) ->
                val book = marks.first().book?.toModel()
                BookmarkGroup(
                    book = BookId(id),
                    info = book,
                    location = (book?.source as? BookSource.InLocation)?.let { locations[it.location.value] },
                    bookmarks = marks.map { it.bookmark.toModel() },
                )
            }
            groups.sorted(sort)
        }

    override fun inBook(book: BookId): Flow<List<Bookmark>> = dao.observeBook(book.value).map { rows -> rows.map { it.toModel() } }

    override suspend fun add(book: BookId, position: ReadingPosition, label: String?, createdAt: Instant): Bookmark {
        val row = bookmarkRow(book, position, label ?: defaultBookmarkLabel(position), createdAt)
        return row.copy(id = dao.insert(row)).toModel()
    }

    override suspend fun rename(id: Long, label: String) = dao.rename(id, label)

    override suspend fun delete(ids: Collection<Long>) = ids.chunked(MAX_BOUND).forEach { dao.delete(it) }

    override suspend fun delete(scope: BookmarkScope) = when (scope) {
        BookmarkScope.All -> dao.deleteAll()
        is BookmarkScope.Books -> scope.books.map { it.value }.chunked(MAX_BOUND).forEach { dao.deleteBooks(it) }
        is BookmarkScope.UnderFolder -> dao.deleteUnder(scope.location.value, normalizePath(scope.folder))
        is BookmarkScope.InLocation -> dao.deleteUnder(scope.location.value, "")
        BookmarkScope.OnDevice -> dao.deleteOnDevice()
    }
}

private fun List<BookmarkGroup>.sorted(sort: BookmarkSort): List<BookmarkGroup> {
    val byPosition = compareBy<Bookmark> { it.position.order }.thenBy { it.createdAt }
    val byPath = compareBy<BookmarkGroup, String>(NaturalOrder) { pathKey(it.info, it.location, it.book) }
    val byTitle = compareBy<BookmarkGroup, String>(NaturalOrder) { it.info?.title ?: "￿" }.then(byPath)
    val byNewest = compareByDescending<BookmarkGroup> { group -> group.bookmarks.maxOf { it.createdAt } }
    val byOldest = compareBy<BookmarkGroup> { group -> group.bookmarks.minOf { it.createdAt } }
    return when (sort) {
        BookmarkSort.PathAscending -> map { it.copy(bookmarks = it.bookmarks.sortedWith(byPosition)) }.sortedWith(byPath)
        BookmarkSort.PathDescending -> map { it.copy(bookmarks = it.bookmarks.sortedWith(byPosition)) }.sortedWith(byPath.reversed())
        BookmarkSort.NameAscending -> map { it.copy(bookmarks = it.bookmarks.sortedWith(byPosition)) }.sortedWith(byTitle)
        BookmarkSort.NameDescending -> map { it.copy(bookmarks = it.bookmarks.sortedWith(byPosition)) }.sortedWith(byTitle.reversed())
        BookmarkSort.Newest -> map { g -> g.copy(bookmarks = g.bookmarks.sortedByDescending { it.createdAt }) }.sortedWith(byNewest)
        BookmarkSort.Oldest -> map { g -> g.copy(bookmarks = g.bookmarks.sortedBy { it.createdAt }) }.sortedWith(byOldest)
    }
}

private val ReadingPosition.order: Long
    get() = when (this) {
        is ReadingPosition.Image -> viewStart.toLong()
        is ReadingPosition.Text -> charOffset
    }

/** SQLite before 3.32, as on API 28, binds at most 999 values in one statement. */
internal const val MAX_BOUND = 900

internal fun bookmarkRow(book: BookId, position: ReadingPosition, label: String, createdAt: Instant): BookmarkRow {
    val (kind, at, total) = position.columns()
    return BookmarkRow(bookId = book.value, positionKind = kind!!, positionAt = at, positionTotal = total, label = label, createdAt = createdAt.toEpochMilli())
}

internal fun BookmarkRow.toModel() = Bookmark(
    id = id,
    book = BookId(bookId),
    position = positionOf(positionKind, positionAt, positionTotal)!!,
    label = label,
    createdAt = Instant.ofEpochMilli(createdAt),
)
