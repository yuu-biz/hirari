package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.BookRepository
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Stores the overrides the readers change (docs/spec.md section 2.9), one for the whole process. A value waits
 * here until it is written, so a reader opened again before that starts from it, not from the older stored one.
 * [scope] outlives every screen, so a change made just before closing is kept.
 */
class BookOverridesStore(private val books: BookRepository, private val scope: CoroutineScope) {
    private val pending = HashMap<BookId, BookOverrides>()
    private val writing = Mutex()

    /** A writer for book [id], starting from the overrides set last, written or not. */
    suspend fun open(id: BookId): OverridesWriter =
        OverridesWriter(id, synchronized(pending) { pending[id] } ?: books.overrides(id).first())

    /** The overrides of one open book, as its reader changes them. */
    inner class OverridesWriter internal constructor(private val id: BookId, initial: BookOverrides) {
        @Volatile
        var current: BookOverrides = initial
            private set

        fun update(transform: (BookOverrides) -> BookOverrides) {
            val next = transform(current)
            if (next == current) return
            current = next
            store(id, next)
        }
    }

    /** Each write stores the value latest when it runs, so writes that start out of order still end on it. */
    private fun store(id: BookId, value: BookOverrides) {
        synchronized(pending) { pending[id] = value }
        scope.launch {
            writing.withLock {
                val latest = synchronized(pending) { pending[id] } ?: return@withLock
                try {
                    books.setOverrides(id, latest)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The database refused; the reader keeps showing the change for this session.
                }
                synchronized(pending) { if (pending[id] == latest) pending.remove(id) }
            }
        }
    }
}
