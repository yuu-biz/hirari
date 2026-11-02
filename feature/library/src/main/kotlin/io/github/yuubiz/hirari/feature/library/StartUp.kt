package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.data.HistoryItem
import io.github.yuubiz.hirari.core.data.LaunchRecord
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReopenLastBook
import io.github.yuubiz.hirari.core.model.address
import kotlinx.coroutines.flow.first

/** The last book a start offers again (docs/spec.md section 4.3, 起動時の動作). */
sealed interface Reopen {
    val request: OpenBookRequest

    /** 尋ねる: the app names [title] and opens the book only on a yes. */
    data class Ask(val title: String, override val request: OpenBookRequest) : Reopen

    /** すぐ開く. */
    data class Now(override val request: OpenBookRequest) : Reopen
}

/**
 * What a start opens on top of home: the folder the file list showed last, scrolled to [reveal], and then the
 * last book. An empty plan starts at home.
 */
data class StartPlan(val folder: FolderKey? = null, val reveal: String? = null, val reopen: Reopen? = null)

/** Starts in a row that did not finish before the next one starts at home with the defaults. */
const val FAILED_STARTS = 3

/**
 * Counts this start and plans it. After [FAILED_STARTS] unfinished starts in a row, it forgets the last folder,
 * puts the view back to its default, and starts at home without the last book.
 */
suspend fun LibraryServices.planStart(): StartPlan {
    val record = launch.beginStart()
    if (record.unfinishedStarts >= FAILED_STARTS) {
        launch.setLastFolder(null)
        settings.update { it.copy(libraryView = GlobalSettings().libraryView) }
        return StartPlan()
    }
    val reopen = settings.settings.first().reopenLastBook
    val last = if (reopen == ReopenLastBook.Off) null else history.observe(HistorySort.Newest).first().firstOrNull()
    return decideStart(record, reopen, last, locations.locations.first())
}

/**
 * The plan from what the last starts left. A folder or book whose place is gone or lost its grant is left out, as
 * is a text book until the text reader exists (M5).
 */
fun decideStart(record: LaunchRecord, reopen: ReopenLastBook, last: HistoryItem?, places: List<LibraryLocation>): StartPlan {
    fun usable(id: LocationId) = (places.firstOrNull { it.id == id } as? LibraryLocation.SafTree)?.permissionHeld == true
    val folder = record.lastFolder?.takeIf { usable(it.location) }?.let { FolderKey(it.location, it.path, it.documentId) }
    val book = last?.book?.takeIf { book ->
        val source = book.source
        book.format !is BookFormat.Text && (source !is BookSource.InLocation || usable(source.location))
    }
    val offered = book?.let { OpenBookRequest(it.address()) }?.let { request ->
        when (reopen) {
            ReopenLastBook.Off -> null
            ReopenLastBook.Ask -> Reopen.Ask(book.title, request)
            ReopenLastBook.Immediately -> Reopen.Now(request)
        }
    }
    val inFolder = (offered?.request?.book as? BookAddress.InLocation)?.takeIf { address ->
        folder != null && address.location == folder.location && childToward(folder.path, address.path) == address.path
    }
    return StartPlan(folder, inFolder?.path, offered)
}
