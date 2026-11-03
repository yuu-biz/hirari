package io.github.yuubiz.hirari.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.data.FolderCover
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
import io.github.yuubiz.hirari.core.model.ThumbSize
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.model.normalizePath
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
        val thumbSize: ThumbSize,
        val sort: LibrarySort,
        val foldersFirst: Boolean,
        val covers: CoverLayout,
        /** The image file chosen as the cover of this folder, if one is. */
        val folderCoverPath: String?,
    ) : FolderContent
}

/** The covers a list shows and the size of their thumbnails (docs/spec.md section 4.4). */
data class CoverLayout(val covers: Covers, val sizePx: Int)

data class FolderUi(val placeName: String, val path: String, val content: FolderContent, val notice: Notice? = null)

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
    /** A document picked or handed over outside a place; its id comes from its first bytes. */
    data class Document(val uri: UriString) : TextAddress

    /** The text at [path] of a place. [documentId] is the provider's id when the caller knows it. */
    data class InLocation(val location: LocationId, val path: String, val documentId: String? = null) : TextAddress

    /** The entry at [entryIndex], in the stored order, of [archive], whose id is [archiveId]. */
    data class ArchiveEntry(val archive: ArchiveFile, val entryIndex: Int, val archiveId: BookId) : TextAddress
}

/** The archive a text entry is read from. */
sealed interface ArchiveFile {
    /** The archive's document, of a SAF place. */
    data class Document(val uri: UriString) : ArchiveFile

    /** The archive at [path] of an SMB place, which has no document. */
    data class OnServer(val location: LocationId, val path: String) : ArchiveFile
}

private sealed interface ListingState {
    data object Loading : ListingState

    data object Slow : ListingState

    data class Done(val listing: Listing) : ListingState
}

private data class Layout(val view: LibraryView, val thumbSize: ThumbSize, val ordering: Ordering)

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
    private var folderDocumentId: String? = folder.documentId
    private var loading: Job? = null

    val state: StateFlow<FolderUi> = flow { emit(services.await()) }
        .flatMapLatest { services ->
            val layout = services.settings.settings
                .map { Layout(it.libraryView, it.thumbSize, Ordering(it.librarySort, it.foldersFirst, it.showDotFiles)) }
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
            combine(place, content, notice) { place, content, notice -> FolderUi(place?.displayName.orEmpty(), folder.path, content, notice) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FolderUi("", folder.path, FolderContent.Loading))

    init {
        list(reconnect = false)
    }

    private fun shown(services: LibraryServices, entries: List<FolderEntry>, layout: Flow<Layout>): Flow<FolderContent> {
        val sorted = layout.map { it.ordering }.distinctUntilChanged().map { entries.toRows(folder.path, it) }.flowOn(compute)
        val context = place.value?.let { coverContext(services, it, entries) } ?: flowOf(null)
        val marked = combine(sorted, services.books.inFolder(folder.location, folder.path), progressOf(services, sorted), context) { rows, indexed, progress, covers ->
            val marked = rows.withMarks(indexed, progress)
            if (covers == null) marked else marked.withCovers(indexed, covers)
        }.flowOn(compute)
        val chosenHere = services.coverIndex.folderCovers(folder.location).map { it[folder.path]?.takeIf { cover -> cover.entry == null }?.imagePath }.distinctUntilChanged()
        return combine(marked, query, layout, chosenHere) { rows, query, layout, chosenHere ->
            val covers = CoverLayout(services.covers, services.covers.sizePx(layout.thumbSize))
            FolderContent.Shown(rows.filtered(query), rows.size, query, layout.view, layout.thumbSize, layout.ordering.sort, layout.ordering.foldersFirst, covers, chosenHere)
        }.flowOn(compute)
    }

    /**
     * What the covers of this listing need: the directions of the books that have one, the global one, and the image
     * chosen for each folder in it, whose file is looked up once off the main thread. The rows are shown before the
     * lookup ends, but a folder with a chosen image shows no cover until it is found, so its cover is never made
     * without the image.
     */
    private fun coverContext(services: LibraryServices, place: LibraryLocation, entries: List<FolderEntry>): Flow<CoverContext> {
        val folders = entries.filter { it.directory }.mapTo(HashSet()) { normalizePath("${folder.path}/${it.name}") }
        val chosen = services.coverIndex.folderCovers(folder.location)
            .map { all -> all.filterKeys { it in folders } }
            .distinctUntilChanged()
            .flatMapLatest { all ->
                flow {
                    if (all.isNotEmpty()) emit(emptyMap<String, ChosenCover>() to all.keys)
                    emit(all.mapNotNull { (path, cover) -> services.covers.chosen(place, cover)?.let { path to it } }.toMap() to emptySet())
                }
            }
        val direction = services.settings.settings.map { it.readingDirection }.distinctUntilChanged()
        return combine(services.coverIndex.directions(), direction, chosen) { directions, default, (chosen, pending) -> CoverContext(place, default, directions, chosen, pending) }
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

    private fun list(reconnect: Boolean) {
        loading?.cancel()
        listing.value = ListingState.Loading
        loading = viewModelScope.launch {
            val slow = launch {
                delay(SLOW_LISTING_MS)
                listing.compareAndSet(ListingState.Loading, ListingState.Slow)
            }
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
            slow.cancel()
            if (result is Listing.Listed) folderDocumentId = result.folderDocumentId
            listing.value = ListingState.Done(result)
        }
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

    fun dismissNotice() {
        notice.value = null
    }

    fun show(message: Notice) {
        notice.value = message
    }

    /** ホーム画面にショートカットを作成 of the long-press sheet, when [row] has one. */
    fun shortcut(row: FileRow): PlaceShortcut? = shortcutFor(row, folder.location)

    /**
     * What tapping [row] opens (docs/spec.md section 4.3), in a place of either kind. An image file opens its folder
     * as one book at that image. A text file opens in the text reader. Moving to another folder clears the filter.
     */
    fun open(row: FileRow, start: StartAt = StartAt.Saved): FileTarget = when (val item = row.item) {
        is FileItem.Folder -> {
            query.value = ""
            FileTarget.Folder(FolderKey(folder.location, row.path, item.entry.documentId))
        }
        is FileItem.Archive -> FileTarget.Book(OpenBookRequest(BookAddress.InLocation(folder.location, row.path, folder = false, item.entry.documentId), start))
        is FileItem.Image -> FileTarget.Book(
            OpenBookRequest(BookAddress.InLocation(folder.location, folder.path, folder = true, folderDocumentId), StartAt.Image(item.entry.name)),
        )
        is FileItem.Text -> FileTarget.Text(TextAddress.InLocation(folder.location, row.path, item.entry.documentId))
    }

    /** 既読にする and 未読にする of the long-press sheet (docs/spec.md sections 2.7 and 4.3). */
    fun mark(row: FileRow, state: ReadState) {
        val place = place.value ?: return
        write { progress.mark(locationBookId(place, row.path), state, clock()) }
    }

    fun pin(row: FileRow) = write { locations.pin(folder.location, row.path, clock()) }

    /** サムネイルを作り直す of the long-press sheet (docs/spec.md section 4.4). */
    fun remake(row: FileRow) {
        val spec = row.cover ?: return
        val size = (state.value.content as? FolderContent.Shown)?.thumbSize ?: return
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
