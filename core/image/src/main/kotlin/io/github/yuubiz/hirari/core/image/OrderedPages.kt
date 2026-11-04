package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** One ordered step includes the disk lookup and archive read. */
internal class PageReadQueue {
    private class Request(val entry: Int) {
        val turn = CompletableDeferred<Unit>()
    }
    private val pending = mutableListOf<Request>()
    private var active: Request? = null
    private var ranks = emptyMap<Int, Int>()

    fun prioritize(entries: List<Int>) = synchronized(this) {
        ranks = entries.withIndex().associate { it.value to it.index }
    }

    suspend fun <T> read(entry: Int, block: suspend () -> T): T {
        val request = Request(entry)
        synchronized(this) { pending += request }
        try {
            // Register requests from the same plan before choosing its head.
            yield()
            synchronized(this) { next() }
            request.turn.await()
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            synchronized(this) {
                pending.remove(request)
                if (active === request) active = null
                next()
            }
        }
    }

    private fun next() {
        if (active != null) return
        val head = pending.minByOrNull { ranks[it.entry] ?: Int.MAX_VALUE } ?: return
        pending.remove(head)
        active = head
        head.turn.complete(Unit)
    }
}

internal class OrderedPages(private val source: PageBytes) : PageBytes {
    private val queue = PageReadQueue()
    override fun prioritize(entries: List<Int>) = queue.prioritize(entries)
    override suspend fun read(page: PageEntry): PageResult<ByteArray> = queue.read(page.entryIndex) { source.read(page) }
    override suspend fun prefix(page: PageEntry, count: Int): PageResult<ByteArray> = queue.read(page.entryIndex) { source.prefix(page, count) }
    override fun close() = source.close()
}
