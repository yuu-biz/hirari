package io.github.yuubiz.hirari.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    private val pending = HashMap<BookId, OverridesPatch>()
    private var revision = 0
    private val writing = Mutex()

    /**
     * A writer for book [id], starting from the stored overrides with the changes not written yet. The stored row and
     * the changes are read as of one [revision], so a change queued or written while the row is read is not applied
     * onto a row that already holds a newer value.
     */
    suspend fun open(id: BookId): OverridesWriter {
        while (true) {
            val (unwritten, seen) = synchronized(pending) { pending[id] to revision }
            val stored = books.overrides(id).first()
            if (synchronized(pending) { revision } == seen) return OverridesWriter(id, unwritten?.applyTo(stored) ?: stored)
        }
    }

    /** The overrides of one open book, as its reader changes them. */
    inner class OverridesWriter internal constructor(private val id: BookId, initial: BookOverrides) {
        /** Snapshot state, so a sheet that shows what the book keeps follows it. */
        var current: BookOverrides by mutableStateOf(initial)
            private set

        /**
         * [transform] sets the fields the reader changed and keeps the others. Only the fields it changes are
         * written, onto the stored row. The screen and the process's scope both change a book's overrides, so one
         * change is applied at a time and none is lost to another.
         */
        @Synchronized
        fun update(transform: (BookOverrides) -> BookOverrides) {
            val next = transform(current)
            val patch = OverridesPatch.between(current, next) ?: return
            current = next
            store(id, patch)
        }
    }

    /** Each write applies every change not written yet, so writes that start out of order still end on the latest. */
    private fun store(id: BookId, change: OverridesPatch) {
        synchronized(pending) {
            pending[id] = pending[id]?.let { it + change } ?: change
            revision++
        }
        scope.launch {
            writing.withLock {
                val latest = synchronized(pending) { pending[id] } ?: return@withLock
                try {
                    books.updateOverrides(id, latest::applyTo)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The database refused. The change stays pending, so a reader opened again in this process
                    // still starts from it, and the next change writes it again.
                    return@withLock
                }
                synchronized(pending) {
                    if (pending[id] === latest) {
                        pending.remove(id)
                        revision++
                    }
                }
            }
        }
    }
}
