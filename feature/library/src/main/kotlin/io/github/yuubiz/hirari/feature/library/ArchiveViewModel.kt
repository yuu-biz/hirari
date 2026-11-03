package io.github.yuubiz.hirari.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.data.FolderCover
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.model.normalizePath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.time.Instant

/** The archive book of a place whose list the screen shows (docs/spec.md section 4.7). */
data class ArchiveKey(val location: LocationId, val path: String, val documentId: String? = null)

data class TextRow(val text: ArchiveText, val mark: ReadMark)

/** [read] is before the stored position, or the whole book is read, so the page shows faded. */
data class PageRow(val page: ArchivePage, val read: Boolean)

sealed interface ArchiveUi {
    data object Loading : ArchiveUi

    data class Failed(val reason: ArchiveFailure) : ArchiveUi

    /**
     * [charset] decodes the names; [chosen] is the user's choice for the book, or null when it is detected.
     * [indexed] tells that the book is in the index, which keeps a chosen cover.
     */
    data class Shown(
        val title: String,
        val charset: Charset,
        val chosen: Charset?,
        val texts: List<TextRow>,
        val pages: List<PageRow>,
        val indexed: Boolean,
        /** The page thumbnails; `null` when the archive's own entry cannot be found, which leaves the numbered squares. */
        val covers: PageCovers?,
        /** The page of this archive chosen as the cover of its folder, if it is. */
        val folderCoverPage: Int?,
        /** Whether a folder cover can be chosen: the archive is in a folder, not at the root of the place. */
        val inFolder: Boolean,
    ) : ArchiveUi
}

/**
 * The thumbnails of the pages (docs/spec.md section 4.7). A page is the archive made with the page as its cover
 * ([base] has none), so the thumbnail of the page the user chose as the cover is the cover itself.
 */
data class PageCovers(val layout: CoverLayout, val base: CoverSpec.Book) {
    fun ref(page: ArchivePage): CoverRef? = if (page.unreadable) null else CoverRef(base.copy(coverEntry = page.entryIndex), layout.sizePx)
}

data class ArchiveScreenUi(val content: ArchiveUi, val notice: Notice? = null)

/** What a tap in the list opens. */
sealed interface ArchiveTarget {
    data class Page(val request: OpenBookRequest) : ArchiveTarget

    data class Text(val address: TextAddress) : ArchiveTarget
}

private sealed interface Opening {
    data object Pending : Opening

    data class Failed(val reason: ArchiveFailure) : Opening

    class Opened(val place: LibraryLocation, val id: BookId, val contents: ArchiveContents, val base: CoverSpec.Book?) : Opening
}

/**
 * The list of one archive book (docs/spec.md section 4.7). The archive is opened once; a charset chosen from the
 * menu decodes the same raw names again and sorts them anew (section 3.4), and the marks follow the progress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModel(
    private val services: Deferred<LibraryServices>,
    val book: ArchiveKey,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val opening = MutableStateFlow<Opening>(Opening.Pending)
    private val notice = MutableStateFlow<Notice?>(null)
    private val title = book.path.substringAfterLast('/')

    val state: StateFlow<ArchiveScreenUi> = opening
        .flatMapLatest { opened ->
            when (opened) {
                Opening.Pending -> flowOf(ArchiveUi.Loading)
                is Opening.Failed -> flowOf(ArchiveUi.Failed(opened.reason))
                is Opening.Opened -> shown(services.await(), opened)
            }
        }
        .let { content -> combine(content, notice, ::ArchiveScreenUi) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ArchiveScreenUi(ArchiveUi.Loading))

    init {
        viewModelScope.launch {
            val services = services.await()
            val place = services.locations.get(book.location) ?: return@launch run { opening.value = Opening.Failed(ArchiveFailure.NotFound) }
            val id = locationBookId(place, book.path)
            val base = async { coverBase(services, place, id) }
            opening.value = when (val result = services.archives.open(place, book.path, book.documentId, id)) {
                is ArchiveOpenResult.Failed -> Opening.Failed(result.reason).also { base.cancel() }
                is ArchiveOpenResult.Opened -> Opening.Opened(place, id, result.contents, base.await())
            }
        }
    }

    /** The archive without a chosen page, dated by its own entry in its folder, or `null` when that cannot be found. */
    private suspend fun coverBase(services: LibraryServices, place: LibraryLocation, id: BookId): CoverSpec.Book? {
        val path = normalizePath(book.path)
        val entry = services.covers.find(place, path) ?: return null
        val direction = services.directionOf(id)
        return CoverSpec.Book(id, entry.modified ?: Instant.EPOCH, entry.size ?: 0, null, direction, CoverOrigin.Archive(place, path, book.documentId ?: entry.documentId))
    }

    private fun shown(services: LibraryServices, opened: Opening.Opened): Flow<ArchiveUi> {
        val contents = opened.contents
        val id = opened.id
        val chosen = services.books.overrides(id).map { it.nameCharset }.distinctUntilChanged()
        val fallback = services.settings.settings.map { Charset.forName(it.fallbackCharset.charsetName) }.distinctUntilChanged()
        val rows = combine(chosen, fallback) { chosen, fallback -> chosen to contents.charset(chosen, fallback) }
            .distinctUntilChanged()
            .mapLatest { (chosen, charset) -> Triple(chosen, charset, contents.rows(charset, id)) }
            .flowOn(compute)
        val progress = rows
            .map { (_, _, rows) -> rows.texts.map { it.id } + id }
            .distinctUntilChanged()
            .flatMapLatest { ids -> services.progress.observe(ids) }
        val indexed = services.books.inFolder(book.location, book.path.substringBeforeLast('/', "")).map { list -> list.any { it.book.id == id } }
        val folder = normalizePath(book.path).substringBeforeLast('/', "")
        val folderCover = if (folder.isEmpty()) flowOf(null) else services.locations.folderCover(book.location, folder)
        val direction = combine(services.coverIndex.directions(), services.settings.settings.map { it.readingDirection }.distinctUntilChanged()) { own, default ->
            own[id] ?: default
        }.distinctUntilChanged()
        val layout = services.settings.settings.map { it.thumbSize }.distinctUntilChanged().map { CoverLayout(services.covers, services.covers.sizePx(it)) }
        val covers = combine(layout, direction) { layout, direction -> opened.base?.let { PageCovers(layout, it.copy(direction = direction)) } }
        return combine(rows, progress, indexed, covers, folderCover) { (chosen, charset, rows), progress, indexed, covers, folderCover ->
            val own = progress[id]
            ArchiveUi.Shown(
                title = title,
                charset = charset,
                chosen = chosen,
                texts = rows.texts.map { TextRow(it, progress[it.id].mark()) },
                pages = rows.pages.map { PageRow(it, own.covers(it.index)) },
                indexed = indexed,
                covers = covers,
                folderCoverPage = folderCover?.takeIf { it.imagePath == normalizePath(book.path) }?.entry,
                inFolder = folder.isNotEmpty(),
            )
        }.flowOn(compute)
    }

    private val opened: Opening.Opened? get() = opening.value as? Opening.Opened

    /**
     * What tapping [page] or a text opens. The readers do not open the books of an SMB place yet, so those say so
     * (docs/spec.md section 4.5).
     */
    fun open(page: ArchivePage): ArchiveTarget? {
        if (opened?.place is LibraryLocation.SmbShare) return null.also { notice.value = Notice.SmbBookNotReady }
        val address = BookAddress.InLocation(book.location, book.path, folder = false, book.documentId)
        return ArchiveTarget.Page(OpenBookRequest(address, StartAt.Page(page.index)))
    }

    fun open(text: ArchiveText): ArchiveTarget? {
        val opened = opened ?: return null
        val uri = opened.contents.documentUri ?: return null.also { notice.value = Notice.SmbBookNotReady }
        return ArchiveTarget.Text(TextAddress.ArchiveEntry(uri, text.entryIndex, opened.id))
    }

    /**
     * 文字コード of the menu (docs/spec.md section 3.4); null goes back to the detected one. The stored position
     * follows its page to the place the new names sort it to (section 2.7).
     */
    fun chooseCharset(charset: Charset?) {
        val opened = opened ?: return
        val id = opened.id
        write {
            val overrides = books.overrides(id).first()
            val fallback = Charset.forName(settings.settings.first().fallbackCharset.charsetName)
            val (before, after) = withContext(compute) {
                listOf(overrides.nameCharset, charset).map { opened.contents.rows(opened.contents.charset(it, fallback), id).pages }
            }
            books.updateOverrides(id) { it.copy(nameCharset = charset) }
            val saved = progress.get(id) ?: return@write
            val moved = saved.movedTo(before, after)
            if (moved != saved) progress.save(listOf(moved))
        }
    }

    /** 未読 and 既読 of a text entry, which reads as a book of its own. */
    fun mark(text: ArchiveText, state: ReadState) = write { progress.mark(text.id, state, clock()) }

    /** ここまで読んだことにする: the stored position becomes [page]; the last page makes the book read. */
    fun readUpTo(page: ArchivePage) {
        val id = opened?.id ?: return
        val count = (state.value.content as? ArchiveUi.Shown)?.pages?.size ?: return
        val state = if (page.index >= count - 1) ReadState.Read else ReadState.Reading
        write { progress.save(listOf(BookProgress(id, state, ReadingPosition.Image(page.index, count), clock()))) }
    }

    /** このページを表紙にする (docs/spec.md section 4.4). */
    fun setCover(page: ArchivePage) {
        val id = opened?.id ?: return
        write { books.setCover(id, page.entryIndex) }
    }

    /**
     * このページをフォルダの表紙にする and its undoing (docs/spec.md section 4.4): the folder that holds the archive
     * shows [page], and its thumbnail is made again.
     */
    fun chooseFolderCover(page: ArchivePage, choose: Boolean) {
        val place = opened?.place ?: return
        val folder = normalizePath(book.path).substringBeforeLast('/', "")
        if (folder.isEmpty()) return
        write { applyFolderCover(place, folder, if (choose) FolderCover(normalizePath(book.path), page.entryIndex) else null) }
    }

    fun dismissNotice() {
        notice.value = null
    }

    private fun write(block: suspend LibraryServices.() -> Unit) {
        viewModelScope.launch { services.await().block() }
    }

    override fun onCleared() {
        opened?.contents?.close()
    }
}

/** Whether the page at [index] lies before the stored position, or the book is read. */
private fun BookProgress?.covers(index: Int): Boolean = when (this?.state) {
    ReadState.Read -> true
    ReadState.Reading -> (position as? ReadingPosition.Image)?.let { index < it.viewStart } == true
    null, ReadState.Unread -> false
}

/** This progress with its position on the page of [before] it named, at that page's place in [after]. */
internal fun BookProgress.movedTo(before: List<ArchivePage>, after: List<ArchivePage>): BookProgress {
    val at = position as? ReadingPosition.Image ?: return this
    val entry = before.getOrNull(at.viewStart)?.entryIndex ?: return this
    val index = after.indexOfFirst { it.entryIndex == entry }.takeIf { it >= 0 } ?: return this
    return copy(position = at.copy(viewStart = index))
}
