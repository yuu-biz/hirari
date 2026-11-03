package io.github.yuubiz.hirari.feature.library

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.image.thumbnail.PagePreview
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.PageShape
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The page whose range is chosen: the indexed book, and the `entryIndex` of the page in it. */
data class CoverRangeKey(val book: BookId, val entry: Int)

enum class CoverRangeFailure {
    /** The book is not in the index, or its place is gone. */
    NotFound,

    /** A text has no cover to choose a range of. */
    NoImages,

    /** The book no longer has that page. */
    PageMissing,

    /** The page cannot be shown, and will not be as long as the file stays the same. */
    Unreadable,

    /** Reading failed for a reason that may pass; opening the screen again tries again. */
    Unavailable,
}

sealed interface CoverRangeUi {
    data object Loading : CoverRangeUi

    data class Failed(val reason: CoverRangeFailure) : CoverRangeUi

    /**
     * [page] is the whole page, fitted into the size the screen asks for; [shape] is its original size. [frame] is
     * where the frame starts: the range stored for this page, or the largest frame of the cell proportion where the
     * automatic cover looks. [ranged] is true when this page is the book's cover with a range, which 範囲をやめる removes.
     */
    class Ready(val page: Bitmap, val shape: PageShape.Known, val frame: CropFrame, val ranged: Boolean) : CoverRangeUi
}

/**
 * 範囲を選んで表紙にする (docs/spec.md section 4.4): shows one page of an indexed book whole and stores the part the
 * user frames as the book's cover. Storing makes the page the cover, with the range, and makes the thumbnail again.
 */
class CoverRangeViewModel(
    private val services: Deferred<LibraryServices>,
    val key: CoverRangeKey,
) : ViewModel() {
    private val mutableState = MutableStateFlow<CoverRangeUi>(CoverRangeUi.Loading)
    val state: StateFlow<CoverRangeUi> = mutableState

    private var spec: CoverSpec.Book? = null
    private var loaded: LibraryServices? = null

    init {
        load()
    }

    fun retry() {
        mutableState.value = CoverRangeUi.Loading
        load()
    }

    private fun load() {
        viewModelScope.launch { mutableState.value = open() }
    }

    private suspend fun open(): CoverRangeUi {
        val services = services.await().also { loaded = it }
        val stored = services.books.get(key.book) ?: return CoverRangeUi.Failed(CoverRangeFailure.NotFound)
        val source = stored.book.source
        val place = (source as? BookSource.InLocation)?.let { services.locations.get(it.location) ?: return CoverRangeUi.Failed(CoverRangeFailure.NotFound) }
        val direction = services.directionOf(key.book)
        val book = coverSpecOf(stored.book, key.entry, place, direction) ?: return CoverRangeUi.Failed(CoverRangeFailure.NoImages)
        spec = book
        return when (val preview = services.covers.preview(book, key.entry, PREVIEW_SIDE_PX)) {
            is PagePreview.Ready -> {
                val opening = openingFrame(stored.coverEntry, stored.coverCrop, key.entry, preview.shape, direction)
                CoverRangeUi.Ready(preview.bitmap, preview.shape, opening.frame, opening.ranged)
            }
            PagePreview.Missing -> CoverRangeUi.Failed(CoverRangeFailure.PageMissing)
            PagePreview.Failed -> CoverRangeUi.Failed(CoverRangeFailure.Unreadable)
            PagePreview.Unavailable -> CoverRangeUi.Failed(CoverRangeFailure.Unavailable)
        }
    }

    /** 確定: the part inside [frame] becomes the book's cover. */
    fun choose(frame: CropFrame) = store(frame.toCrop())

    /** 範囲をやめる: this page stays the cover, whole as the automatic cover shows a page. */
    fun dropRange() = store(null)

    /**
     * Stores the choice and makes the thumbnail again at every size, on the process's scope so leaving the screen
     * does not leave the cover chosen but its thumbnail old. The new range gives the cover another key, so the lists
     * show it as soon as it is stored; making it again first spares them the wait.
     */
    private fun store(crop: CoverCrop?) {
        val book = spec ?: return
        val services = loaded ?: return
        services.appScope.launch {
            services.books.setCover(key.book, key.entry, crop)
            val thumbSize = services.settings.settings.first().thumbSize
            services.covers.regenerate(book.copy(coverEntry = key.entry, coverCrop = crop), services.covers.sizesFrom(thumbSize))
        }
    }

    private companion object {
        /** The longest side of the page shown: enough to place a frame by eye on any phone, and 16 MB at most. */
        const val PREVIEW_SIDE_PX = 2048
    }
}
