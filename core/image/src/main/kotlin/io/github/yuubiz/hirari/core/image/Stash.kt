package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.source.smb.SmbPrefetchCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * [server] with every entry it reads kept in [stash], and read from there when it holds the entry. One entry is read
 * by one caller at a time, so a prefetch and a view that want the same page read it from the server once.
 */
internal class StashedPages(private val server: PageBytes, private val stash: DiskStash) : PageBytes {
    private val entries = ConcurrentHashMap<Int, Mutex>()

    override suspend fun read(page: PageEntry): PageResult<ByteArray> = locked(page) {
        withContext(Dispatchers.IO) { stash.cache.get(key(page)) }?.let { return@locked PageResult.Ready(it) }
        server.read(page).also { read ->
            if (read is PageResult.Ready) withContext(Dispatchers.IO) { stash.cache.put(key(page), read.image) }
        }
    }

    suspend fun holds(page: PageEntry): Boolean = withContext(Dispatchers.IO) { stash.cache.has(key(page)) }

    suspend fun fill(page: PageEntry): Boolean = locked(page) {
        if (holds(page)) return@locked true
        val read = try {
            server.read(page)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@locked false
        }
        read is PageResult.Ready && withContext(Dispatchers.IO) { stash.cache.put(key(page), read.image) }
    }

    override fun close() = server.close()

    private suspend inline fun <T> locked(page: PageEntry, block: () -> T): T =
        entries.computeIfAbsent(page.entryIndex) { Mutex() }.withLock { block() }

    private fun key(page: PageEntry) = SmbPrefetchCache.Key(stash.book, page.entryIndex)
}
