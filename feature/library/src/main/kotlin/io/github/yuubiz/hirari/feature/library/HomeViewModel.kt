package io.github.yuubiz.hirari.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.data.BookmarkGroup
import io.github.yuubiz.hirari.core.data.HistoryItem
import io.github.yuubiz.hirari.core.data.LibraryBook
import io.github.yuubiz.hirari.core.data.PinnedFolder
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.address
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Ready<T>(val value: T) : Loadable<T>
}

/** A folder of a place, as the file list opens it. Without [documentId] a SAF list looks the path up. */
data class FolderKey(val location: LocationId, val path: String, val documentId: String? = null)

data class ContinueItem(val book: LibraryBook, val percent: Int, val cover: CoverSpec.Book? = null)

/** A pinned folder with its place, or `null` when the place is gone. */
data class PinnedItem(val pin: PinnedFolder, val place: LibraryLocation?)

/** The 本棚 tab, top to bottom (docs/spec.md section 4.2). */
data class Shelf(
    val continueReading: List<ContinueItem>,
    val pinned: List<PinnedItem>,
    val places: List<LibraryLocation>,
    val covers: CoverLayout,
)

/** [specs] has the cover of each book of [items] that has one, by its id. */
data class HistoryTab(val sort: HistorySort, val items: List<HistoryItem>, val covers: CoverLayout, val specs: Map<BookId, CoverSpec.Book>)

/** What the covers of the home tabs need besides the books (docs/spec.md section 4.4). */
private class CoverEnv(val layout: CoverLayout, val directions: Map<BookId, ReadingDirection>, val default: ReadingDirection) {
    fun spec(book: Book, coverEntry: Int?, place: LibraryLocation?): CoverSpec.Book? =
        coverSpecOf(book, coverEntry, place, directions[book.id] ?: default)
}

data class BookmarksTab(val sort: BookmarkSort, val groups: List<BookmarkGroup>)

/** A one-time message for a snackbar. */
enum class Notice { GrantRefused, BookMissing, PlaceUnavailable, NotInPlace, TextNotReady, ShortcutMissing, ShortcutUnsupported, IncomingUnreadable }

/** What a tap on a book of the home screen opens: the image reader, or the text reader for a text. */
sealed interface HomeTarget {
    data class Book(val request: OpenBookRequest) : HomeTarget

    data class Text(val address: TextAddress) : HomeTarget
}

data class HomeUi(
    val shelf: Loadable<Shelf> = Loadable.Loading,
    val history: Loadable<HistoryTab> = Loadable.Loading,
    val bookmarks: Loadable<BookmarksTab> = Loadable.Loading,
    val notice: Notice? = null,
)

/** The three tabs of the home screen (docs/spec.md sections 4.2 and 4.6). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val services: Deferred<LibraryServices>) : ViewModel() {
    private val notice = MutableStateFlow<Notice?>(null)

    val state: StateFlow<HomeUi> = flow { emit(services.await()) }
        .flatMapLatest { services ->
            combine(services.shelf(), services.historyTab(), services.bookmarksTab(), notice, ::HomeUi)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUi())

    private fun LibraryServices.coverEnv(): Flow<CoverEnv> =
        combine(settings.settings.map { it.thumbSize }.distinctUntilChanged(), coverIndex.directions(), settings.settings.map { it.readingDirection }.distinctUntilChanged()) { size, directions, default ->
            CoverEnv(CoverLayout(covers, covers.sizePx(size)), directions, default)
        }

    private fun LibraryServices.shelf(): Flow<Loadable<Shelf>> =
        combine(books.continueReading(CONTINUE_READING), locations.pinned, locations.locations, coverEnv()) { reading, pins, places, env ->
            val byId = places.associateBy { it.id }
            Shelf(
                continueReading = reading.map { item ->
                    val place = (item.book.source as? BookSource.InLocation)?.let { byId[it.location] }
                    ContinueItem(item, item.progress?.percent() ?: 0, env.spec(item.book, item.coverEntry, place))
                },
                pinned = pins.map { PinnedItem(it, byId[it.location]) },
                places = places,
                covers = env.layout,
            )
        }.loadable()

    private fun LibraryServices.historyTab(): Flow<Loadable<HistoryTab>> =
        settings.settings.map { it.historySort }.distinctUntilChanged()
            .flatMapLatest { sort ->
                combine(history.observe(sort), coverEnv()) { items, env ->
                    val specs = HashMap<BookId, CoverSpec.Book>()
                    for (item in items) item.book?.let { env.spec(it, item.coverEntry, item.location) }?.let { specs[item.entry.book] = it }
                    HistoryTab(sort, items, env.layout, specs)
                }
            }
            .loadable()

    private fun LibraryServices.bookmarksTab(): Flow<Loadable<BookmarksTab>> =
        settings.settings.map { it.bookmarkSort }.distinctUntilChanged()
            .flatMapLatest { sort -> bookmarks.observe(sort).map { BookmarksTab(sort, it) } }
            .loadable()

    fun dismissNotice() {
        notice.value = null
    }

    /** Shows [message], as the app does when a shortcut found nothing (docs/spec.md section 4.11). */
    fun show(message: Notice) {
        notice.value = message
    }

    /**
     * Registers the picked [tree] as a place with its persisted grant (docs/spec.md section 4.5). Picking a
     * registered tree again gives that place its grant back instead of adding it twice (section 7).
     */
    fun addTree(tree: UriString) = write {
        if (!grants.take(tree)) {
            notice.value = Notice.GrantRefused
            return@write
        }
        val known = locations.locations.first().any { it is LibraryLocation.SafTree && it.treeUri == tree }
        if (known) locations.syncGrants(grants.held()) else locations.addSafTree(grants.name(tree), tree)
    }

    fun renamePlace(id: LocationId, name: String) = write { locations.rename(id, name.trim()) }

    /**
     * Removes the place and releases its grant, or drops the connection of a server whose password went with it;
     * its books keep their progress, bookmarks and history (section 7).
     */
    fun removePlace(id: LocationId) = write {
        when (val removed = locations.delete(id)) {
            is LibraryLocation.SafTree -> {
                val shared = locations.locations.first().any { it is LibraryLocation.SafTree && it.treeUri == removed.treeUri }
                if (!shared) grants.release(removed.treeUri)
            }
            is LibraryLocation.SmbShare -> smb.forget(id)
            null -> Unit
        }
    }

    fun setHistorySort(sort: HistorySort) = write { settings.update { it.copy(historySort = sort) } }

    fun removeFromHistory(book: BookId) = write { history.remove(book) }

    fun clearHistory() = write { history.clear() }

    fun setBookmarkSort(sort: BookmarkSort) = write { settings.update { it.copy(bookmarkSort = sort) } }

    fun renameBookmark(id: Long, label: String) = write { bookmarks.rename(id, label.trim()) }

    fun deleteBookmark(id: Long) = write { bookmarks.delete(listOf(id)) }

    /**
     * What opening [book] at [start] asks of a reader, or `null` with a notice when it cannot open: the index lost
     * it, or its place is gone or lost its grant (docs/spec.md section 7). A text opens in the text reader at its
     * stored position.
     */
    fun request(book: Book?, start: StartAt = StartAt.Saved): HomeTarget? {
        if (book == null) return null.also { notice.value = Notice.BookMissing }
        val source = book.source
        if (source is BookSource.InLocation && !usable(source.location)) return null.also { notice.value = Notice.PlaceUnavailable }
        if (book.format is BookFormat.Text) {
            return HomeTarget.Text(
                when (source) {
                    is BookSource.InLocation -> TextAddress.InLocation(source.location, source.path)
                    is BookSource.External -> TextAddress.Document(source.uri)
                },
            )
        }
        return HomeTarget.Book(OpenBookRequest(book.address(), start))
    }

    fun request(group: BookmarkGroup, position: ReadingPosition): HomeTarget? = when (position) {
        is ReadingPosition.Image -> request(group.info, StartAt.Page(position.viewStart))
        is ReadingPosition.Text -> null.also { notice.value = Notice.TextNotReady }
    }

    /** フォルダを開く of docs/spec.md section 4.6: the folder that holds [book] in its place. */
    fun folderOf(book: Book?): FolderKey? {
        if (book == null) return null.also { notice.value = Notice.BookMissing }
        val source = book.source as? BookSource.InLocation ?: return null.also { notice.value = Notice.NotInPlace }
        if (!usable(source.location)) return null.also { notice.value = Notice.PlaceUnavailable }
        return FolderKey(source.location, source.path.substringBeforeLast('/', missingDelimiterValue = ""))
    }

    fun folderOf(pin: PinnedItem): FolderKey? {
        if (!usable(pin.pin.location)) return null.also { notice.value = Notice.PlaceUnavailable }
        return FolderKey(pin.pin.location, pin.pin.path)
    }

    private fun usable(location: LocationId): Boolean {
        val places = (state.value.shelf as? Loadable.Ready)?.value?.places ?: return true
        return places.firstOrNull { it.id == location }.usable()
    }

    private fun write(block: suspend LibraryServices.() -> Unit) {
        viewModelScope.launch { services.await().block() }
    }
}

/** A place opens when it is there and, for a SAF place, still has its grant. */
internal fun LibraryLocation?.usable(): Boolean = when (this) {
    null -> false
    is LibraryLocation.SafTree -> permissionHeld
    is LibraryLocation.SmbShare -> true
}

internal fun <T> Flow<T>.loadable(): Flow<Loadable<T>> = map<T, Loadable<T>> { Loadable.Ready(it) }.onStart { emit(Loadable.Loading) }

private const val CONTINUE_READING = 10
internal const val STOP_TIMEOUT_MS = 5_000L
