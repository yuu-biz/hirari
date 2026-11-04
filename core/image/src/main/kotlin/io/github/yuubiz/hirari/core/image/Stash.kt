package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.source.smb.SmbPrefetchCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where the entries of the archive of book [id] on a server go on disk once read (docs/spec.md section 5.3). The
 * archive's [size] and last write time [modified] tell its entries apart from those of an archive it replaced.
 */
class DiskStash(val cache: SmbPrefetchCache, id: BookId, size: Long, modified: Instant) {
    internal val book = "${id.value}/$size/${modified.toEpochMilli()}"
}

/** The SMB prefetch layer of an open book (docs/spec.md section 5.3): its pages' bytes on disk. */
interface PageStash {
    /** Whether the bytes of the page at [index] are on disk. */
    suspend fun holds(index: Int): Boolean

    /** Whether there is capacity for the page before downloading it. */
    suspend fun canFill(index: Int): Boolean = true

    /** Reads the page at [index] onto disk unless it is there. */
    suspend fun fill(index: Int): Fill
}

/** What [PageStash.fill] did with a page. */
enum class Fill {
    /** The page's bytes are on disk. */
    OnDisk,

    /** This entry could not be read; later entries may still succeed. */
    NotKept,

    /** The stash cannot accept writes. */
    NoCapacity,

    /** Reading the page ran out of memory (docs/spec.md section 6.1). */
    OutOfMemory,

    /** The connection to the server broke while reading the page (docs/spec.md section 6.5). */
    ConnectionLost,
}

/**
 * [server] with every entry it reads kept in [stash], and read from there when it holds the entry. Callers that ask
 * for an entry while it is being read wait for that read, so a prefetch and a view that want the same page read it
 * from the server once, whether or not the disk keeps it.
 */
internal class StashedPages(
    private val server: PageBytes,
    private val stash: DiskStash,
    private val persist: suspend (SmbPrefetchCache.Key, ByteArray) -> Boolean = { key, bytes -> stash.cache.put(key, bytes) },
) : PageBytes {
    /** What one read of an entry got, and whether the disk holds it now. */
    private class Fetched(val result: PageResult<ByteArray>, val kept: Deferred<Boolean>)

    /** The reads in progress by entry. One that ends without a result gives null, and its waiters read for themselves. */
    private val reading = ConcurrentHashMap<Int, CompletableDeferred<Fetched?>>()

    private val queue = PageReadQueue()
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val cached = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ByteArray, Boolean>())

    override fun fromStash(bytes: ByteArray): Boolean = cached.containsKey(bytes)

    override suspend fun invalidate(page: PageEntry) {
        reading[page.entryIndex]?.await()?.kept?.await()
        withContext(Dispatchers.IO) { stash.cache.remove(key(page)) }
    }

    override suspend fun prefix(page: PageEntry, count: Int): PageResult<ByteArray> =
        queue.read(page.entryIndex) { server.prefix(page, count) }

    override fun prioritize(entries: List<Int>) = queue.prioritize(entries)

    override suspend fun read(page: PageEntry): PageResult<ByteArray> = fetch(page).result

    suspend fun canFill(page: PageEntry): Boolean = withContext(Dispatchers.IO) {
        stash.cache.canWrite(page.declaredSize ?: (1536L * 1024))
    }

    suspend fun holds(page: PageEntry): Boolean = withContext(Dispatchers.IO) { stash.cache.has(key(page)) }

    suspend fun fill(page: PageEntry): Fill {
        return try {
            val estimated = page.declaredSize ?: (1536L * 1024)
            if (!stash.cache.fits(estimated)) return Fill.NotKept
            if (!holds(page) && !canFill(page)) return Fill.NoCapacity
            val fetched = fetch(page)
            when {
                fetched.kept.await() -> Fill.OnDisk
                fetched.result is PageResult.Ready ->
                    if (stash.cache.fits(fetched.result.image.size.toLong())) Fill.NoCapacity else Fill.NotKept
                (fetched.result as? PageResult.Failed)?.reason == FailureReason.ConnectionLost -> Fill.ConnectionLost
                else -> Fill.NotKept
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Fill.NotKept
        } catch (_: OutOfMemoryError) {
            Fill.OutOfMemory
        }
    }

    override fun close() {
        writes.cancel()
        server.close()
    }

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
                return queue.read(page.entryIndex) { readThrough(page) }.also { fetched = it }
            } finally {
                // Removed before completing: a waiter can resume inside complete() on this thread (the main thread's
                // immediate dispatcher) and must not find this finished read again, or it loops without end.
                val result = fetched
                if (result == null) reading.remove(page.entryIndex, mine)
                mine.complete(result)
                if (result != null) result.kept.invokeOnCompletion { reading.remove(page.entryIndex, mine) }
            }
        }
    }

    private suspend fun readThrough(page: PageEntry): Fetched {
        withContext(Dispatchers.IO) { stash.cache.get(key(page), page.declaredSize) }?.let {
            cached[it] = true
            return Fetched(PageResult.Ready(it), CompletableDeferred(true))
        }
        val read = server.read(page)
        val kept = writes.async { read is PageResult.Ready && persist(key(page), read.image) }
        return Fetched(read, kept)
    }

    private fun key(page: PageEntry) = SmbPrefetchCache.Key(stash.book, page.entryIndex)
}
