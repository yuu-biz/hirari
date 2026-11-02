package io.github.yuubiz.hirari.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.ThumbSize
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.locationBookId
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
    ) : FolderContent
}

data class FolderUi(val placeName: String, val path: String, val content: FolderContent, val notice: Notice? = null)

/** What a tap on a row opens. */
sealed interface FileTarget {
    data class Folder(val key: FolderKey) : FileTarget

    data class Book(val request: OpenBookRequest) : FileTarget

    data class Text(val address: TextAddress) : FileTarget
}

/** A text the text reader opens (docs/spec.md section 4.9): a document, or a text entry of an archive document. */
sealed interface TextAddress {
    data class Document(val uri: UriString) : TextAddress

    /** The entry at [entryIndex], in the archive's stored order. */
    data class ArchiveEntry(val archive: UriString, val entryIndex: Int) : TextAddress
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
        load()
    }

    private fun shown(services: LibraryServices, entries: List<FolderEntry>, layout: Flow<Layout>): Flow<FolderContent> {
        val sorted = layout.map { it.ordering }.distinctUntilChanged().map { entries.toRows(folder.path, it) }.flowOn(compute)
        val marked = combine(sorted, services.books.inFolder(folder.location, folder.path), progressOf(services, sorted)) { rows, indexed, progress ->
            rows.withMarks(indexed, progress)
        }.flowOn(compute)
        return combine(marked, query, layout) { rows, query, layout ->
            FolderContent.Shown(rows.filtered(query), rows.size, query, layout.view, layout.thumbSize, layout.ordering.sort, layout.ordering.foldersFirst)
        }.flowOn(compute)
    }

    /** The progress of the books and texts of the [rows] by path, which a book marked before it was ever opened has too. */
    private fun progressOf(services: LibraryServices, rows: Flow<List<FileRow>>): Flow<Map<String, BookProgress>> {
        val tree = place.value ?: return flowOf(emptyMap())
        return rows
            .map { list -> list.filter { it.item is FileItem.Archive || it.item is FileItem.Text }.associateBy({ locationBookId(tree, it.path) }, { it.path }) }
            .distinctUntilChanged()
            .flatMapLatest { paths -> services.progress.observe(paths.keys).map { found -> found.mapKeys { (id, _) -> paths.getValue(id) } } }
    }

    /** Lists the folder again, as after a failure. */
    fun load() {
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
                is LibraryLocation.SmbShare -> services.smb.list(location, folder.path)
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

    /**
     * What tapping [row] opens (docs/spec.md section 4.3). An image file opens its folder as one book at that
     * image. A text file opens in the text reader. Moving to another folder clears the filter. The readers do not
     * open the books of an SMB place yet, so those say so (section 4.5).
     */
    fun open(row: FileRow, start: StartAt = StartAt.Saved): FileTarget? = when (val item = row.item) {
        is FileItem.Folder -> {
            query.value = ""
            FileTarget.Folder(FolderKey(folder.location, row.path, item.entry.documentId))
        }
        else -> if (place.value is LibraryLocation.SmbShare) null.also { notice.value = Notice.SmbBookNotReady } else openFile(item, row, start)
    }

    private fun openFile(item: FileItem, row: FileRow, start: StartAt): FileTarget? = when (item) {
        is FileItem.Folder -> null
        is FileItem.Archive -> FileTarget.Book(OpenBookRequest(BookAddress.InLocation(folder.location, row.path, folder = false, item.entry.documentId), start))
        is FileItem.Image -> FileTarget.Book(
            OpenBookRequest(BookAddress.InLocation(folder.location, folder.path, folder = true, folderDocumentId), StartAt.Image(item.entry.name)),
        )
        is FileItem.Text -> {
            val tree = (place.value as? LibraryLocation.SafTree)?.treeUri
            val documentId = item.entry.documentId
            if (tree == null || documentId == null) null else FileTarget.Text(TextAddress.Document(services.getCompleted().documents.of(tree, documentId)))
        }
    }

    /** 既読にする and 未読にする of the long-press sheet (docs/spec.md sections 2.7 and 4.3). */
    fun mark(row: FileRow, state: ReadState) {
        val place = place.value ?: return
        write { progress.mark(locationBookId(place, row.path), state, clock()) }
    }

    fun pin(row: FileRow) = write { locations.pin(folder.location, row.path, clock()) }

    private fun write(block: suspend LibraryServices.() -> Unit) {
        viewModelScope.launch { services.await().block() }
    }
}

/** docs/spec.md section 4.3, 読み込み. */
const val SLOW_LISTING_MS = 300L
