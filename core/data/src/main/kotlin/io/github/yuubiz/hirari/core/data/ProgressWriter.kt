package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Batches the progress the reader reports on every view change (docs/spec.md section 2.7). The first
 * [submit] after a write opens a window, and the end of the window stores the latest progress of each
 * book, so continuous page turns still reach the database every [window].
 *
 * [scope] outlives every screen and runs on `Dispatchers.IO` in the app.
 */
class ProgressWriter(
    private val repository: ProgressRepository,
    private val scope: CoroutineScope,
    private val window: Duration = 500.milliseconds,
) {
    private val pending = LinkedHashMap<BookId, BookProgress>()
    private var windowOpen = false
    private var generation = 0
    private val writing = Mutex()

    fun submit(progress: BookProgress) {
        val opened = synchronized(pending) {
            pending[progress.book] = progress
            if (windowOpen) null else generation.also { windowOpen = true }
        }
        if (opened != null) {
            scope.launch {
                delay(window)
                write(window = opened)
            }
        }
    }

    /** Stores the pending progress now. Call it from `onStop`: the write finishes even if the caller is gone. */
    fun flush(): Job = scope.launch { write(window = null) }

    /** [window] is the generation of the window whose timer calls this, or null for a flush. */
    private suspend fun write(window: Int?) = writing.withLock {
        val batch = synchronized(pending) {
            // A flush already wrote this window; the next submit opens a new one with its own timer.
            if (window != null && window != generation) return@withLock
            windowOpen = false
            generation++
            pending.values.toList().also { pending.clear() }
        }
        if (batch.isEmpty()) return@withLock
        try {
            repository.save(batch)
        } catch (e: Exception) {
            synchronized(pending) { batch.forEach { pending.putIfAbsent(it.book, it) } }
            if (e is CancellationException) throw e
        }
    }
}
