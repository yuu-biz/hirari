package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.source.smb.SmbPrefetchCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where the entries of an archive on a server go on disk once read (docs/spec.md section 5.3). [book] tells the
 * book's entries apart from those of every other book in [cache].
 */
class DiskStash(val cache: SmbPrefetchCache, val book: String)

/** The SMB prefetch layer of an open book (docs/spec.md section 5.3): its pages' bytes on disk. */
interface PageStash {
    /** Whether the bytes of the page at [index] are on disk. */
    suspend fun holds(index: Int): Boolean

    /** Reads the page at [index] from the book onto disk unless it is there. Whether it is there now. */
    suspend fun fill(index: Int): Boolean
}

/**
 * [server] with every entry it reads kept in [stash], and read from there when it holds the entry. Callers that ask
 * for an entry while it is being read wait for that read, so a prefetch and a view that want the same page read it
 * from the server once, whether or not the disk keeps it.
 */
internal class StashedPages(private val server: PageBytes, private val stash: DiskStash) : PageBytes {
    /** What one read of an entry got, and whether the disk holds it now. */
    private class Fetched(val result: PageResult<ByteArray>, val kept: Boolean)

    /** The reads in progress by entry. One that ends without a result gives null, and its waiters read for themselves. */
    private val reading = ConcurrentHashMap<Int, CompletableDeferred<Fetched?>>()

    override suspend fun read(page: PageEntry): PageResult<ByteArray> = fetch(page).result

    suspend fun holds(page: PageEntry): Boolean = withContext(Dispatchers.IO) { stash.cache.has(key(page)) }

    suspend fun fill(page: PageEntry): Boolean {
        if (holds(page)) return true
        return try {
            fetch(page).kept
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override fun close() = server.close()

    private suspend fun fetch(page: PageEntry): Fetched {
        while (true) {
            val mine = CompletableDeferred<Fetched?>()
            val running = reading.putIfAbsent(page.entryIndex, mine)
            if (running != null) {
                running.await()?.let { return it }
                continue
            }
            var fetched: Fetched? = null
            try {
                return readThrough(page).also { fetched = it }
            } finally {
                mine.complete(fetched)
                reading.remove(page.entryIndex, mine)
            }
        }
    }

    private suspend fun readThrough(page: PageEntry): Fetched {
        withContext(Dispatchers.IO) { stash.cache.get(key(page)) }?.let { return Fetched(PageResult.Ready(it), kept = true) }
        val read = server.read(page)
        val kept = read is PageResult.Ready && withContext(Dispatchers.IO) { stash.cache.put(key(page), read.image) }
        return Fetched(read, kept)
    }

    private fun key(page: PageEntry) = SmbPrefetchCache.Key(stash.book, page.entryIndex)
}
