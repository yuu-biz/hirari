package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.BookRepository
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Stores the overrides of book [id] as the reader changes them (docs/spec.md section 2.9). Each write stores the
 * value current when it runs, so writes that start out of order still end on the latest one. [scope] outlives the
 * screen, so a change made just before closing is kept.
 */
class OverridesWriter(
    private val id: BookId,
    initial: BookOverrides,
    private val books: BookRepository,
    private val scope: CoroutineScope,
) {
    private val writing = Mutex()

    @Volatile
    var current: BookOverrides = initial
        private set

    fun update(transform: (BookOverrides) -> BookOverrides) {
        val next = transform(current)
        if (next == current) return
        current = next
        scope.launch {
            writing.withLock {
                try {
                    books.setOverrides(id, current)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The database refused; the reader keeps showing the change for this session.
                }
            }
        }
    }
}
