package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.BookRepository
import io.github.yuubiz.hirari.core.data.HistoryRepository
import io.github.yuubiz.hirari.core.model.Book
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What the reader leaves in the index for one opened book: the book in the index when it opens (docs/spec.md
 * section 2.2), and its place in the history when the reader stops or closes (sections 2.8 and 4.6), stamped with
 * the time it opened. [scope] outlives the screen, so the writes finish after the reader is gone. A failed write is dropped:
 * the index and the history are conveniences, and reading the book again writes them again.
 */
internal class ReadingSession(
    private val book: Book,
    private val openedAt: Instant,
    private val historyLimit: Int,
    private val books: BookRepository,
    private val history: HistoryRepository,
    private val scope: CoroutineScope,
) {
    private val stopped = AtomicBoolean(false)
    private val indexed: Job = scope.launch { quietly { books.record(book) } }

    /** Moves the book to the newest place of the history. Stopping again, or closing after a stop, writes nothing more. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        scope.launch {
            indexed.join()
            quietly { history.record(book.id, openedAt, historyLimit) }
        }
    }

    private suspend fun quietly(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

/**
 * Hands a stop to the session even when it comes first: the reader can stop while the book is still opening, and the
 * process may die before the next stop. Main thread only.
 */
internal class StopRelay {
    private var stopped = false
    private var session: ReadingSession? = null

    fun stop() {
        stopped = true
        session?.stop()
    }

    fun attach(session: ReadingSession) {
        this.session = session
        if (stopped) session.stop()
    }
}
