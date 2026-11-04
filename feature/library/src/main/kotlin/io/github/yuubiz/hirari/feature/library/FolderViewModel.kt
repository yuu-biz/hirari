package io.github.yuubiz.hirari.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.image.PageCandidate
import io.github.yuubiz.hirari.core.image.pageCandidate
import io.github.yuubiz.hirari.core.data.FolderCover
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.model.normalizePath
import io.github.yuubiz.hirari.core.ui.FirstPageTrace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the list shows of a folder (docs/spec.md section 4.3). */
sealed interface FolderContent {
    /** The first [SLOW_LISTING_MS]: nothing yet, so a quick folder does not flash a spinner. */
    data object Loading : FolderContent

    /** The listing takes long; the screen shows progress and a way to cancel. */
    data object SlowLoading : FolderContent

    data class Failed(val reason: ListFailure) : FolderContent

    data class Shown(
        val rows: List<FileRow>,
        /** Rows before the filter. */
        val total: Int,
        val filter: String,
        val view: LibraryView,
        val look: ListLook,
        val sort: LibrarySort,
        val foldersFirst: Boolean,
        val covers: CoverLayout,
        /** The image file chosen as the cover of this folder, if one is. */
        val folderCoverPath: String?,
        /** Whether the list starts with the item that goes up to the parent folder (docs/spec.md section 4.3, 親フォルダの項目). */
        val parentItem: Boolean = false,
    ) : FolderContent
}

/** The covers a list shows and the size of their thumbnails (docs/spec.md section 4.4). */
data class CoverLayout(val covers: Covers, val sizePx: Int)

/**
 * [canSave] is whether the place is an SMB server, whose items 端末に保存 copies (docs/spec.md section 4.5). [pinnedId] is
 * the pin of this folder on the shelf, if it has one. [refreshing] is whether a 読み直し is listing the folder under the
 * rows still shown.
 */
data class FolderUi(
    val placeName: String,
    val path: String,
    val content: FolderContent,
    val notice: Notice? = null,
    val canSave: Boolean = false,
    val pinnedId: Long? = null,
    val refreshing: Boolean = false,
)

/** What a tap on a row opens. */
sealed interface FileTarget {
    data class Folder(val key: FolderKey) : FileTarget

    data class Book(val request: OpenBookRequest) : FileTarget

    data class Text(val address: TextAddress) : FileTarget
}

/**
 * A text the text reader opens (docs/spec.md section 4.9), with what its id is made of (section 2.2), so the list,
 * the reader, the history and a shortcut keep its progress under one id.
 */
sealed interface TextAddress {
    val startOffset: Long?

    /** A document picked or handed over outside a place; its id comes from its first bytes. */
    data class Document(val uri: UriString, override val startOffset: Long? = null) : TextAddress

    /** The text at [path] of a place. [documentId] is the provider's id when the caller knows it. */
    data class InLocation(val location: LocationId, val path: String, val documentId: String? = null, override val startOffset: Long? = null) : TextAddress

    /** The entry at [entryIndex], in the stored order, of [archive], whose id is [archiveId]. */
    data class ArchiveEntry(val archive: ArchiveFile, val entryIndex: Int, val archiveId: BookId, override val startOffset: Long? = null) : TextAddress
}

/** The archive a text entry is read from. */
sealed interface ArchiveFile {
    /** The archive's document, of a SAF place. */
    data class Document(val uri: UriString) : ArchiveFile

    /** The archive at [path] of a device or SMB place. */
    data class InLocation(val location: LocationId, val path: String) : ArchiveFile
}

private sealed interface ListingState {
    data object Loading : ListingState

    data object Slow : ListingState

    data class Done(val listing: Listing) : ListingState
}

private data class Layout(val view: LibraryView, val look: ListLook, val ordering: Ordering, val showParentItem: Boolean)

/**
 * One folder of a SAF place. The folder is listed once; sorting, filtering and the read-state marks are worked
 * out from that listing on [compute], so changing them asks the provider nothing (docs/spec.md section 4.3).
 * The marks follow the index, so they change when the reader closes, without a restart (M2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderViewModel(
    private val services: Deferred<LibraryServices>,
    val folder: FolderKey,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val listing = MutableStateFlow<ListingState>(ListingState.Loading)
    private val place = MutableStateFlow<LibraryLocation?>(null)
    private val notice = MutableStateFlow<Notice?>(null)
    private val refreshing = MutableStateFlow(false)
    private var folderDocumentId: String? = folder.documentId
    private var loading: Job? = null

    val state: StateFlow<FolderUi> = flow { emit(services.await()) }
        .flatMapLatest { services ->
            val layout = services.settings.settings
                .map { Layout(it.libraryView, it.listLook(), Ordering(it.librarySort, it.foldersFirst, it.showDotFiles), it.showParentItem) }
                .distinctUntilChanged()
            val content = listing.flatMapLatest { state ->
                when (state) {
                    ListingState.Loading -> flowOf(FolderContent.Loading)
                    ListingState.Slow -> flowOf(FolderContent.SlowLoading)
                    is ListingState.Done -> when (val result = state.listing) {
                        is Listing.Failed -> flowOf(FolderContent.Failed(result.reason))
                        is Listing.Listed -> shown(services, result.entries, layout)
                    }
                }
            }
            val pinnedId = services.locations.pinned
                .map { pins -> pins.firstOrNull { it.location == folder.location && normalizePath(it.path) == normalizePath(folder.path) }?.id }
                .distinctUntilChanged()
            combine(place, content, notice, pinnedId, refreshing) { place, content, notice, pinnedId, refreshing ->
                FolderUi(place?.displayName.orEmpty(), folder.path, content, notice, canSave = place is LibraryLocation.SmbShare, pinnedId, refreshing)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FolderUi("", folder.path, FolderContent.Loading))

    /** The saves to the device under way, from any folder (docs/spec.md section 4.5). */
    val activeSaves: StateFlow<List<SaveJob>> = flow { emit(services.await()) }
        .flatMapLatest { it.saves.jobs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Takes how the next save ended that no screen has shown, waiting for one. */
    suspend fun nextSaveResult(): SaveFinished = services.await().saves.nextUnshown()

    init {
        list(reconnect = false)
    }

    private fun shown(services: LibraryServices, entries: List<FolderEntry>, layout: Flow<Layout>): Flow<FolderContent> {
        val sorted = layout.map { it.ordering }.distinctUntilChanged().map { entries.toRows(folder.path, it) }.flowOn(compute)
        val context = place.value?.let { coverContext(services, it) } ?: flowOf(null)
        val marked = combine(sorted, services.books.inFolder(folder.location, folder.path), progressOf(services, sorted), context) { rows, indexed, progress, covers ->
            val marked = rows.withMarks(indexed, progress)
            if (covers == null) marked else marked.withCovers(indexed, covers)
        }.flowOn(compute)
        val chosenHere = services.coverIndex.folderCovers(folder.location).map { it[folder.path]?.takeIf { cover -> cover.entry == null }?.imagePath }.distinctUntilChanged()
        return combine(marked, query, layout, chosenHere) { rows, query, layout, chosenHere ->
            val covers = CoverLayout(services.covers, services.covers.sizePx(layout.look.thumbSize))
            val parentItem = layout.showParentItem && folderTrail("", folder.path).parent != null
            FolderContent.Shown(rows.filtered(query), rows.size, query, layout.view, layout.look, layout.ordering.sort, layout.ordering.foldersFirst, covers, chosenHere, parentItem)
        }.flowOn(compute)
    }

    /** Carries chosen paths without resolving them; only visible cover requests do I/O (section 4.4). */
    private fun coverContext(services: LibraryServices, place: LibraryLocation): Flow<CoverContext> {
        val direction = services.settings.settings.map { it.readingDirection }.distinctUntilChanged()
        return combine(services.coverIndex.directions(), direction, services.coverIndex.folderCovers(folder.location)) { directions, default, chosen ->
            CoverContext(place, default, directions, chosen)
        }
    }

    /** The progress of the books and texts of the [rows] by path, which a book marked before it was ever opened has too. */
    private fun progressOf(services: LibraryServices, rows: Flow<List<FileRow>>): Flow<Map<String, BookProgress>> {
        val tree = place.value ?: return flowOf(emptyMap())
        return rows
            .map { list -> list.filter { it.item is FileItem.Archive || it.item is FileItem.Text }.associateBy({ locationBookId(tree, it.path) }, { it.path }) }
            .distinctUntilChanged()
            .flatMapLatest { paths -> services.progress.observe(paths.keys).map { found -> found.mapKeys { (id, _) -> paths.getValue(id) } } }
    }

    /**
     * 読み直す: lists the folder again, as after a failure. A server whose reconnects all failed is reconnected
     * first (docs/spec.md section 6.5), which the slow listing's progress and 取り消し cover.
     */
    fun load() = list(reconnect = true)

    /**
     * 読み直す of the top bar's menu and the pull on the list (docs/spec.md section 4.3, 読み直し): while the rows are
     * shown, lists the folder again under them, so the scroll and the filter stay, and swaps the rows when it ends. A
     * folder not shown is listed as by [load]. A 読み直し asked while one runs is the same one.
     */
    fun refresh() {
        if (refreshing.value) return
        if ((listing.value as? ListingState.Done)?.listing !is Listing.Listed) return load()
        loading?.cancel()
        refreshing.value = true
        loading = viewModelScope.launch {
            try {
                listing.value = ListingState.Done(fetch(reconnect = true))
            } finally {
                refreshing.value = false
            }
        }
    }

    private fun list(reconnect: Boolean) {
        loading?.cancel()
        refreshing.value = false
        listing.value = ListingState.Loading
        loading = viewModelScope.launch {
            val slow = launch {
                delay(SLOW_LISTING_MS)
                listing.compareAndSet(ListingState.Loading, ListingState.Slow)
            }
            val result = fetch(reconnect)
            slow.cancel()
            listing.value = ListingState.Done(result)
        }
    }

    private suspend fun fetch(reconnect: Boolean): Listing {
        val services = services.await()
        val location = services.locations.get(folder.location)
        if (location != null) place.value = location
        val result = when (location) {
            null -> Listing.Failed(ListFailure.NotFound)
            is LibraryLocation.SafTree ->
                if (location.permissionHeld) services.folders.list(location.treeUri, folder.path, folder.documentId) else Listing.Failed(ListFailure.NoPermission)
            is LibraryLocation.SmbShare -> {
                if (reconnect) services.smb.reconnect(location)
                services.smb.list(location, folder.path)
            }
        }
        if (result is Listing.Listed) folderDocumentId = result.folderDocumentId
        return result
    }

    /** 取り消し while the listing is slow. The screen closes next. */
    fun cancel() {
        loading?.cancel()
    }

    fun setFilter(text: String) {
        query.value = text
    }

    fun setView(view: LibraryView) = write { settings.update { it.copy(libraryView = view) } }

    fun setSort(sort: LibrarySort) = write { settings.update { it.copy(librarySort = sort) } }

    fun setFoldersFirst(first: Boolean) = write { settings.update { it.copy(foldersFirst = first) } }

    /** Saves a change made in 表示の調整 (docs/spec.md section 4.3); the list follows the settings. */
    fun changeLook(change: (GlobalSettings) -> GlobalSettings) = write { settings.update(change) }

    fun dismissNotice() {
        notice.value = null
    }

    fun show(message: Notice) {
        notice.value = message
    }

    /** ホーム画面にショートカットを作成 of the long-press sheet, when [row] has one. */
    fun shortcut(row: FileRow): PlaceShortcut? = shortcutFor(row, folder.location)

    /** The folder book offered by the sheet, after one listing of its direct children (section 4.3). */
    suspend fun folderBook(row: FileRow): OpenBookRequest? {
        if (row.item !is FileItem.Folder) return null
        val services = services.await()
        val location = place.value ?: return null
        val result = when (location) {
            is LibraryLocation.SafTree -> if (location.permissionHeld) {
                services.folders.list(location.treeUri, row.path, row.item.entry.documentId)
            } else return null
            is LibraryLocation.SmbShare -> services.smb.list(location, row.path)
        }
        if (result !is Listing.Listed || result.entries.none { pageCandidate(it.name, it.directory) == PageCandidate.Image }) return null
        return OpenBookRequest(BookAddress.InLocation(folder.location, row.path, folder = true, result.folderDocumentId), StartAt.Saved)
    }

    /**
     * What tapping [row] opens (docs/spec.md section 4.3), in a place of either kind. An image file opens its folder
     * as one book at that image. A text file opens in the text reader. Moving to another folder clears the filter.
     */
    fun open(row: FileRow, start: StartAt = StartAt.Saved): FileTarget = when (val item = row.item) {
        is FileItem.Folder -> {
            query.value = ""
            FileTarget.Folder(FolderKey(folder.location, row.path, item.entry.documentId))
        }
        is FileItem.Archive -> {
            FirstPageTrace.begin()
            FileTarget.Book(OpenBookRequest(BookAddress.InLocation(folder.location, row.path, folder = false, item.entry.documentId), start))
        }
        is FileItem.Image -> {
            FirstPageTrace.begin()
            FileTarget.Book(
                OpenBookRequest(BookAddress.InLocation(folder.location, folder.path, folder = true, folderDocumentId), StartAt.Image(item.entry.name)),
            )
        }
        is FileItem.Text -> FileTarget.Text(TextAddress.InLocation(folder.location, row.path, item.entry.documentId))
    }

    /** 既読にする and 未読にする of the long-press sheet (docs/spec.md sections 2.7 and 4.3). */
    fun mark(row: FileRow, state: ReadState) {
        val place = place.value ?: return
        write { progress.mark(locationBookId(place, row.path), state, clock()) }
    }

    fun pin(row: FileRow) = write { locations.pin(folder.location, row.path, clock()) }

    /** このフォルダをピン留め and ピン留めを外す of the top bar's menu (docs/spec.md section 4.3). */
    fun togglePin() {
        val pinned = state.value.pinnedId
        write { if (pinned == null) locations.pin(folder.location, folder.path, clock()) else locations.unpin(pinned) }
    }

    /** ホーム画面にショートカットを作成 of the top bar's menu: a shortcut to this folder, named as the title names it. */
    fun folderShortcut(): PlaceShortcut = PlaceShortcut(folder.location, folder.path, folder = true, folderTrail(state.value.placeName, folder.path).current)

    /** Where the picker for 端末に保存 starts: the folder chosen last time. */
    suspend fun lastSaveFolder(): UriString? = services.await().launch.lastSaveFolder()

    /**
     * 端末に保存 of the long-press sheet (docs/spec.md section 4.5): saves [row] into the tree [destination] the user
     * picked. Takes the write grant and remembers the folder; the write grant of the folder before it is given back
     * unless a save still writes there.
     */
    fun save(row: FileRow, destination: UriString) {
        val place = place.value as? LibraryLocation.SmbShare ?: return
        write {
            val before = launch.lastSaveFolder()
            grants.takeWrite(destination)
            launch.setLastSaveFolder(destination)
            if (before != null && before != destination && saves.jobs.value.none { it.destination == before }) grants.releaseWrite(before)
            saves.start(SaveRequest(place, row.path, row.item.entry.name, row.item is FileItem.Folder, destination))
        }
        notice.value = Notice.SaveStarted
    }

    fun cancelSave(id: Long) {
        viewModelScope.launch { services.await().saves.cancel(id) }
    }

    /** サムネイルを作り直す of the long-press sheet (docs/spec.md section 4.4). */
    fun remake(row: FileRow) {
        val spec = row.cover ?: return
        val size = (state.value.content as? FolderContent.Shown)?.look?.thumbSize ?: return
        write { covers.regenerate(spec, covers.sizesFrom(size)) }
    }

    /**
     * フォルダの表紙にする and its undoing, for an image file of this folder (docs/spec.md section 4.4): the folder
     * shows the image, and its thumbnail is made again.
     */
    fun chooseFolderCover(row: FileRow, choose: Boolean) {
        val place = place.value ?: return
        write { applyFolderCover(place, folder.path, if (choose) FolderCover(row.path) else null) }
    }

    private fun write(block: suspend LibraryServices.() -> Unit) {
        viewModelScope.launch { services.await().block() }
    }
}

/** docs/spec.md section 4.3, 読み込み. */
const val SLOW_LISTING_MS = 300L
