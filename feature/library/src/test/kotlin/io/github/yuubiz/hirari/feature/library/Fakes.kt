package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.BookRepository
import io.github.yuubiz.hirari.core.data.BookmarkGroup
import io.github.yuubiz.hirari.core.data.BookmarkRepository
import io.github.yuubiz.hirari.core.data.BookmarkScope
import io.github.yuubiz.hirari.core.data.FolderCover
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.data.HistoryItem
import io.github.yuubiz.hirari.core.data.HistoryRepository
import io.github.yuubiz.hirari.core.data.LastFolder
import io.github.yuubiz.hirari.core.data.LaunchRecord
import io.github.yuubiz.hirari.core.data.LaunchRepository
import io.github.yuubiz.hirari.core.data.LibraryBook
import io.github.yuubiz.hirari.core.data.LocationRepository
import io.github.yuubiz.hirari.core.data.PasswordChange
import io.github.yuubiz.hirari.core.data.PinnedFolder
import io.github.yuubiz.hirari.core.data.ProgressRepository
import io.github.yuubiz.hirari.core.data.SettingsRepository
import io.github.yuubiz.hirari.core.data.SmbServer
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.Bookmark
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.CredentialRef
import io.github.yuubiz.hirari.core.model.HistoryEntry
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.source.ByteSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.nio.charset.Charset
import java.time.Instant

internal val T0: Instant = Instant.parse("2026-01-01T00:00:00Z")

internal fun book(location: LocationId, path: String, pages: Int? = 10, container: Container = Container.Zip) = Book(
    id = BookId("id:$path"),
    source = BookSource.InLocation(location, path),
    format = BookFormat.Images(container),
    title = path.substringAfterLast('/'),
    byteSize = 1,
    modified = T0,
    pageCount = pages,
)

internal fun reading(book: Book, viewStart: Int, pages: Int = 10, state: ReadState = ReadState.Reading, at: Instant = T0) =
    BookProgress(book.id, state, ReadingPosition.Image(viewStart, pages), at)

internal fun List<LibraryBook>.progressByPath(): Map<String, BookProgress> =
    mapNotNull { book -> book.progress?.let { (book.book.source as BookSource.InLocation).path to it } }.toMap()

internal class FakeLocations : LocationRepository {
    val rows = MutableStateFlow<List<LibraryLocation>>(emptyList())
    val pins = MutableStateFlow<List<PinnedFolder>>(emptyList())
    private var next = 1L

    override val locations: Flow<List<LibraryLocation>> = rows

    override suspend fun get(id: LocationId) = rows.value.firstOrNull { it.id == id }

    override suspend fun addSafTree(displayName: String, treeUri: UriString): LocationId {
        val id = LocationId(next++)
        rows.update { it + LibraryLocation.SafTree(id, displayName, treeUri) }
        return id
    }

    /** The passwords as stored, by reference, and the password changes as the edits asked for them. */
    val secrets = mutableMapOf<CredentialRef, String>()
    val changes = mutableListOf<PasswordChange>()

    override suspend fun addSmbShare(server: SmbServer, password: CharArray?): LocationId {
        val id = LocationId(next++)
        val ref = password?.let { CredentialRef("ref-${id.value}").also { ref -> secrets[ref] = String(it) } }
        rows.update { it + server.toPlace(id, ref) }
        return id
    }

    override suspend fun updateSmbShare(id: LocationId, server: SmbServer, password: PasswordChange) {
        changes += password
        val kept = (get(id) as? LibraryLocation.SmbShare)?.credential
        val ref = when (password) {
            PasswordChange.Keep -> kept
            PasswordChange.Remove -> null.also { secrets.remove(kept) }
            is PasswordChange.Set -> (kept ?: CredentialRef("ref-${id.value}")).also { secrets[it] = String(password.secret) }
        }
        rows.update { list -> list.map { if (it.id == id) server.toPlace(id, ref) else it } }
    }

    private fun SmbServer.toPlace(id: LocationId, ref: CredentialRef?) =
        LibraryLocation.SmbShare(id, displayName, host, port, share, basePath, domain, user, ref)

    override suspend fun rename(id: LocationId, displayName: String) = rows.update { list ->
        list.map { if (it.id == id && it is LibraryLocation.SafTree) it.copy(displayName = displayName) else it }
    }

    override suspend fun delete(id: LocationId): LibraryLocation? {
        val row = get(id) ?: return null
        rows.update { list -> list.filterNot { it.id == id } }
        pins.update { list -> list.filterNot { it.location == id } }
        return row
    }

    override suspend fun syncGrants(granted: Set<UriString>) = rows.update { list ->
        list.map { if (it is LibraryLocation.SafTree) it.copy(permissionHeld = it.treeUri in granted) else it }
    }

    override val pinned: Flow<List<PinnedFolder>> = pins

    override suspend fun pin(location: LocationId, path: String, at: Instant) =
        pins.update { it + PinnedFolder(it.size + 1L, location, path) }

    override suspend fun unpin(id: Long) = pins.update { list -> list.filterNot { it.id == id } }

    override fun folderCover(location: LocationId, path: String): Flow<FolderCover?> = MutableStateFlow(null)

    override suspend fun setFolderCover(location: LocationId, path: String, cover: FolderCover?) = Unit
}

/** The index and the progress in one place, as the database joins them. */
internal class FakeBooks : BookRepository {
    val books = MutableStateFlow<Map<BookId, Book>>(emptyMap())
    val progress = MutableStateFlow<Map<BookId, BookProgress>>(emptyMap())

    fun put(vararg list: Book) = books.update { it + list.associateBy { book -> book.id } }

    fun put(vararg list: BookProgress) = progress.update { it + list.associateBy { p -> p.book } }

    private val joined = combine(books, progress) { books, progress -> books.values.map { LibraryBook(it, null, progress[it.id]) } }

    override suspend fun record(book: Book) = put(book)

    override suspend fun get(id: BookId): LibraryBook? = books.value[id]?.let { LibraryBook(it, null, progress.value[id]) }

    override fun inFolder(location: LocationId, folder: String): Flow<List<LibraryBook>> = joined.map { list ->
        list.filter {
            val source = it.book.source
            source is BookSource.InLocation && source.location == location && source.path.substringBeforeLast('/', "") == folder
        }
    }

    override fun continueReading(limit: Int): Flow<List<LibraryBook>> = joined.map { list ->
        list.filter { it.progress?.state == ReadState.Reading }.sortedByDescending { it.progress!!.updatedAt }.take(limit)
    }

    override suspend fun setCover(id: BookId, entry: Int?) = Unit

    val overrides = MutableStateFlow<Map<BookId, BookOverrides>>(emptyMap())

    override fun overrides(id: BookId): Flow<BookOverrides> = overrides.map { it[id] ?: BookOverrides() }

    override suspend fun setOverrides(id: BookId, overrides: BookOverrides) = this.overrides.update { it + (id to overrides) }

    val progressRepository = object : ProgressRepository {
        override suspend fun get(book: BookId): BookProgress? = progress.value[book]

        override fun observe(book: BookId): Flow<BookProgress?> = progress.map { it[book] }

        override fun observe(books: Collection<BookId>): Flow<Map<BookId, BookProgress>> = progress.map { it.filterKeys(books::contains) }

        override suspend fun save(progress: Collection<BookProgress>) = put(*progress.toTypedArray())

        override suspend fun mark(book: BookId, state: ReadState, at: Instant) {
            val position = if (state == ReadState.Unread) null else progress.value[book]?.position
            put(BookProgress(book, state, position, at))
        }
    }
}

internal class FakeHistory(private val books: FakeBooks) : HistoryRepository {
    val entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    override fun observe(sort: HistorySort): Flow<List<HistoryItem>> = combine(entries, books.books, books.progress) { entries, books, progress ->
        val items = entries.sortedByDescending { it.openedAt }.map { HistoryItem(it, books[it.book], null, progress[it.book]) }
        if (sort == HistorySort.Oldest) items.reversed() else items
    }

    override suspend fun record(book: BookId, openedAt: Instant, limit: Int) =
        entries.update { list -> (list.filterNot { it.book == book } + HistoryEntry(book, openedAt)).takeLast(limit) }

    override suspend fun remove(book: BookId) = entries.update { list -> list.filterNot { it.book == book } }

    override suspend fun clear() {
        entries.value = emptyList()
    }
}

internal class FakeBookmarks : BookmarkRepository {
    val marks = MutableStateFlow<List<Bookmark>>(emptyList())

    override fun observe(sort: BookmarkSort): Flow<List<BookmarkGroup>> =
        marks.map { list -> list.groupBy { it.book }.map { (book, marks) -> BookmarkGroup(book, null, null, marks) } }

    override fun inBook(book: BookId): Flow<List<Bookmark>> = marks.map { list -> list.filter { it.book == book } }

    override suspend fun add(book: BookId, position: ReadingPosition, label: String?, createdAt: Instant): Bookmark =
        Bookmark(marks.value.size + 1L, book, position, label ?: "", createdAt).also { mark -> marks.update { it + mark } }

    override suspend fun rename(id: Long, label: String) = marks.update { list -> list.map { if (it.id == id) it.copy(label = label) else it } }

    override suspend fun delete(ids: Collection<Long>) = marks.update { list -> list.filterNot { it.id in ids } }

    override suspend fun delete(scope: BookmarkScope) = Unit
}

internal class FakeSettings : SettingsRepository {
    val value = MutableStateFlow(GlobalSettings())
    override val settings: Flow<GlobalSettings> = value

    override suspend fun update(transform: (GlobalSettings) -> GlobalSettings) = value.update(transform)
}

internal class FakeGrants : TreeGrants {
    val held = mutableSetOf<UriString>()
    var refuse = false
    val names = mutableMapOf<UriString, String>()

    override suspend fun take(tree: UriString): Boolean = !refuse && held.add(tree).let { true }

    override suspend fun release(tree: UriString) {
        held.remove(tree)
    }

    var heldFails = false

    override suspend fun held(): Set<UriString> = if (heldFails) throw IllegalStateException("the system did not answer") else held.toSet()

    override suspend fun name(tree: UriString): String = names[tree] ?: tree.value.substringAfterLast('/')
}

/** Each folder by path; a missing path fails as not found, and every path fails with [failure] when it is set. */
internal class FakeLister : FolderLister {
    val folders = mutableMapOf<String, List<FolderEntry>>()
    var calls = 0
    var delayMs = 0L
    var failure: ListFailure? = null

    override suspend fun list(tree: UriString, path: String, documentId: String?): Listing {
        calls++
        if (delayMs > 0) delay(delayMs)
        failure?.let { return Listing.Failed(it) }
        val entries = folders[path] ?: return Listing.Failed(ListFailure.NotFound)
        return Listing.Listed(documentId ?: "doc:$path", entries)
    }
}

/**
 * SMB places by path below their folder; what each test was asked with, and the forgotten places. A [lost] server
 * fails every listing as unreachable until a [reconnect] finds it back.
 */
internal class FakeSmb : SmbPlaces {
    val folders = mutableMapOf<String, List<FolderEntry>>()
    var failure: ListFailure? = null
    var lost = false
    val reconnects = mutableListOf<LocationId>()
    var result: SmbTestResult = SmbTestResult.Connected
    val listed = mutableListOf<Pair<LibraryLocation.SmbShare, String>>()
    val tests = mutableListOf<Triple<SmbServer, String?, CredentialRef?>>()
    val forgotten = mutableListOf<LocationId>()

    override suspend fun list(place: LibraryLocation.SmbShare, path: String): Listing {
        listed += place to path
        if (lost) return Listing.Failed(ListFailure.Unreachable)
        failure?.let { return Listing.Failed(it) }
        val entries = folders[path] ?: return Listing.Failed(ListFailure.NotFound)
        return Listing.Listed(null, entries)
    }

    override suspend fun reconnect(place: LibraryLocation.SmbShare) {
        reconnects += place.id
        lost = false
    }

    override suspend fun test(server: SmbServer, password: CharArray?, stored: CredentialRef?): SmbTestResult {
        tests += Triple(server, password?.let(::String), stored)
        password?.fill('\u0000')
        return result
    }

    override suspend fun open(place: LibraryLocation.SmbShare, path: String): ByteSource = throw java.io.FileNotFoundException(path)

    override suspend fun forget(id: LocationId) {
        forgotten += id
    }
}

/**
 * An archive whose entry names read as [names] gives them for each charset, in stored order. The detected charset is
 * [detected]; the reader decodes in [reader].
 */
internal class FakeContents(
    private val names: Map<Charset, List<String>>,
    private val detected: Charset,
    private val reader: Charset = detected,
    override val documentUri: UriString? = UriString("content://tree/manga/document/book.zip"),
) : ArchiveContents {
    var closed = false

    override val entries: List<ArchiveItem> = names.getValue(detected).indices.map { ArchiveItem(it, byteArrayOf(it.toByte()), 100L + it, isDirectory = false, unreadable = false) }

    override fun charset(override: Charset?, fallback: Charset): Charset = override ?: detected

    override fun readerCharset(): Charset = reader

    override fun name(item: ArchiveItem, charset: Charset): String = names.getValue(charset)[item.entryIndex]

    override suspend fun isImage(item: ArchiveItem): Boolean = false

    override fun close() {
        closed = true
    }
}

internal class FakeArchives : ArchiveOpener {
    var result: ArchiveOpenResult = ArchiveOpenResult.Failed(ArchiveFailure.NotFound)
    val opened = mutableListOf<Pair<String, BookId>>()

    override suspend fun open(place: LibraryLocation, path: String, documentId: String?, book: BookId): ArchiveOpenResult {
        opened += path to book
        return result
    }
}

internal class FakeLaunch : LaunchRepository {
    var record = LaunchRecord()

    override suspend fun beginStart(): LaunchRecord = record.also { record = it.copy(unfinishedStarts = it.unfinishedStarts + 1) }

    override suspend fun finishStart() {
        record = record.copy(unfinishedStarts = 0)
    }

    override suspend fun setLastFolder(folder: LastFolder?) {
        record = record.copy(lastFolder = folder)
    }
}

internal class Fixture {
    val locations = FakeLocations()
    val books = FakeBooks()
    val history = FakeHistory(books)
    val bookmarks = FakeBookmarks()
    val settings = FakeSettings()
    val grants = FakeGrants()
    val lister = FakeLister()
    val smb = FakeSmb()
    val archives = FakeArchives()
    val launch = FakeLaunch()
    val services = LibraryServices(locations, books, history, bookmarks, books.progressRepository, settings, grants, lister, smb, archives, launch, { tree, id -> UriString("${tree.value}/document/$id") }, clock = { T0 })
}
