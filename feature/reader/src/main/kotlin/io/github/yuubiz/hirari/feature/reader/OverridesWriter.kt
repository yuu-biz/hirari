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
 * Stores the overrides the readers change (docs/spec.md section 2.9), one for the whole process. A change waits
 * here until it is written, so a reader opened again before that starts from it, not from the older stored value.
 * Only the changed fields are written, onto the stored row, so a field the archive list stored meanwhile is kept.
 * [scope] outlives every screen, so a change made just before closing is kept.
 */
class BookOverridesStore(private val books: BookRepository, private val scope: CoroutineScope) {
    private val pending = HashMap<BookId, (BookOverrides) -> BookOverrides>()
    private val writing = Mutex()

    /** A writer for book [id], starting from the stored overrides with the changes not written yet. */
    suspend fun open(id: BookId): OverridesWriter {
        val unwritten = synchronized(pending) { pending[id] }
        val stored = books.overrides(id).first()
        return OverridesWriter(id, unwritten?.invoke(stored) ?: stored)
    }

    /** The overrides of one open book, as its reader changes them. */
    inner class OverridesWriter internal constructor(private val id: BookId, initial: BookOverrides) {
        @Volatile
        var current: BookOverrides = initial
            private set

        /**
         * [transform] sets the fields the reader changed and keeps the others. It runs again on the stored row when
         * the change is written.
         */
        fun update(transform: (BookOverrides) -> BookOverrides) {
            val next = transform(current)
            if (next == current) return
            current = next
            store(id, transform)
        }
    }

    /** Each write applies every change not written yet, so writes that start out of order still end on the latest. */
    private fun store(id: BookId, change: (BookOverrides) -> BookOverrides) {
        synchronized(pending) { pending[id] = pending[id]?.let { before -> { change(before(it)) } } ?: change }
        scope.launch {
            writing.withLock {
                val latest = synchronized(pending) { pending[id] } ?: return@withLock
                try {
                    books.updateOverrides(id, latest)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The database refused. The change stays pending, so a reader opened again in this process
                    // still starts from it, and the next change writes it again.
                    return@withLock
                }
                synchronized(pending) { if (pending[id] === latest) pending.remove(id) }
            }
        }
    }
}
