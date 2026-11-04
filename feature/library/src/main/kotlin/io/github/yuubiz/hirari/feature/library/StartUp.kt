package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.data.HistoryItem
import io.github.yuubiz.hirari.core.data.LaunchRecord
import io.github.yuubiz.hirari.core.data.OpeningBook
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
import kotlin.coroutines.cancellation.CancellationException

/** The last book a start offers again (docs/spec.md section 4.3, 起動時の動作). */
sealed interface Reopen {
    val request: OpenBookRequest

    /**
     * 尋ねる: the app names [title] and opens the book only on a yes. [suspect]: the app ended while a reader opened
     * this book, and it has not shown since (docs/spec.md section 6.1), which the question says.
     */
    data class Ask(val title: String, override val request: OpenBookRequest, val suspect: Boolean = false) : Reopen

    /** すぐ開く. */
    data class Now(override val request: OpenBookRequest) : Reopen
}

/** What a start shows (docs/spec.md section 4.3, 起動時の動作). */
sealed interface StartPlan {
    /** The book a reader was opening when the app ended (docs/spec.md section 6.1), which the start names. */
    val diedOpening: OpeningBook? get() = null

    /**
     * The screens the system kept when the process ended, as they were, but for the reader when the app ended while
     * a reader opened [diedOpening]: that reader may be what failed.
     */
    data class Restore(override val diedOpening: OpeningBook? = null) : StartPlan

    /**
     * Home alone, and on top of it, when the setting asks for it, the folder the file list showed last, scrolled to
     * [reveal], and then the last book. An empty plan starts at home.
     */
    data class Open(
        val folder: FolderKey? = null,
        val reveal: String? = null,
        val reopen: Reopen? = null,
        override val diedOpening: OpeningBook? = null,
    ) : StartPlan

    /**
     * What the last starts left could not be read or counted: home alone with [Notice.StartUnplanned], and none of the
     * screens the system kept, since one of them may be what failed.
     */
    data object Unplanned : StartPlan
}

/** Starts in a row that did not finish before the next one starts at home with the defaults. */
const val FAILED_STARTS = 3

/**
 * Counts this start and plans it. A start [restoring] the screens the system kept keeps them. After
 * [FAILED_STARTS] unfinished starts in a row, it forgets the last folder, puts the view back to its default, and
 * starts at home without the last book, even over kept screens. The places first take the grants the system
 * holds now, so a place that lost its grant is not opened (docs/spec.md section 7). When the system does not
 * answer, the stored grants stand until the next start, since a start that fails here would never be counted. When
 * the record of the starts or the settings cannot be read or written, the start is [StartPlan.Unplanned].
 */
suspend fun LibraryServices.planStart(restoring: Boolean = false): StartPlan = try {
    plan(restoring)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    StartPlan.Unplanned
}

private suspend fun LibraryServices.plan(restoring: Boolean): StartPlan {
    val current = settings.settings.first()
    if (settings.takeReadFailure() != null) return StartPlan.Unplanned
    try {
        locations.syncGrants(grants.held())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
    val record = launch.beginStart()
    if (launch.takeFailure() != null) return StartPlan.Unplanned
    if (record.unfinishedStarts >= FAILED_STARTS) {
        launch.setLastFolder(null)
        if (launch.takeFailure() != null) return StartPlan.Unplanned
        settings.update { it.copy(libraryView = GlobalSettings().libraryView) }
        return StartPlan.Open(diedOpening = record.diedOpening)
    }
    if (restoring) return StartPlan.Restore(record.diedOpening)
    val reopen = current.reopenLastBook
    val last = if (reopen == ReopenLastBook.Off || current.historyLimit == 0) null else history.observe(HistorySort.Newest).first().firstOrNull()
    return decideStart(record, current.reopenLastFolder, reopen, last, locations.locations.first())
}

/**
 * The plan from what the last starts left. The last folder is left out unless [reopenFolder]. A folder or book
 * whose place is gone or lost its grant is left out, as is a text book until the text reader exists (M5). The
 * suspect of [record] is asked about, never opened at once (docs/spec.md section 6.1).
 */
fun decideStart(
    record: LaunchRecord,
    reopenFolder: Boolean,
    reopen: ReopenLastBook,
    last: HistoryItem?,
    places: List<LibraryLocation>,
): StartPlan.Open {
    fun usable(id: LocationId) = places.firstOrNull { it.id == id }.usable()
    val folder = record.lastFolder?.takeIf { reopenFolder && usable(it.location) }?.let { FolderKey(it.location, it.path, it.documentId) }
    val book = last?.book?.takeIf { book ->
        val source = book.source
        book.format !is BookFormat.Text && (source !is BookSource.InLocation || usable(source.location))
    }
    val offered = book?.let { OpenBookRequest(it.address()) }?.let { request ->
        val suspect = record.suspect?.isBook(request.book) == true
        when (reopen) {
            ReopenLastBook.Off -> null
            ReopenLastBook.Ask -> Reopen.Ask(book.title, request, suspect)
            ReopenLastBook.Immediately -> if (suspect) Reopen.Ask(book.title, request, suspect = true) else Reopen.Now(request)
        }
    }
    val inFolder = (offered?.request?.book as? BookAddress.InLocation)?.takeIf { address ->
        folder != null && address.location == folder.location && childToward(folder.path, address.path) == address.path
    }
    return StartPlan.Open(folder, inFolder?.path, offered, record.diedOpening)
}
