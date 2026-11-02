package io.github.yuubiz.hirari.core.image.thumbnail

import android.content.Context
import android.os.storage.StorageManager
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.model.NaturalOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Thumbnails for the lists of the library (docs/spec.md sections 4.4 and 5.4): finds one in the [ThumbnailStore],
 * and otherwise makes it from the cover page and stores it.
 *
 * The work is not tied to the caller. At most two thumbnails are made at once from local files and one from SMB
 * ([ThumbLane]), on a decode dispatcher of their own. A request that asks for a thumbnail already being made
 * waits for that one. Cancelling a request, as a list does when the item scrolls out, stops the work once nobody
 * else waits for it. Visible requests are served before nearby ones.
 *
 * Two passes serve a list (section 4.4). [cached] only looks in the store, and is cheap: call it for every
 * visible item first. [thumbnail] also makes what is missing. [observe] does both for one item and holds the
 * thumbnail in the store for as long as the item is shown.
 *
 * Every function is safe to call from the main thread.
 */
class Thumbnails internal constructor(
    private val store: ThumbnailStore,
    private val maker: ThumbnailMaker,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
) {
    private val bookFlights = Flights<ThumbnailKey, ThumbResult>(scope)
    private val folderFlights = Flights<FolderRun, ThumbResult?>(scope)
    private val folderGenerations = ConcurrentHashMap<ThumbnailKey, Long>()
    private val folderWrites = Mutex()
    private val gates = ThumbLane.entries.associateWith { PriorityGate(it.parallelism) }
    private val unavailableUntil = ConcurrentHashMap<ThumbnailKey, Long>()

    /**
     * Pass 1: the thumbnail for [request] if the store has it or knows it failed, and `null` if it has to be made.
     * It does not list a folder: a folder has a thumbnail once pass 2 has searched it.
     */
    suspend fun cached(request: ThumbRequest): ThumbResult? = resolve(request, Mode.CacheOnly)

    /**
     * Pass 2: the thumbnail for [request], made if the store does not have it. Cancelling the call cancels the
     * work unless another request waits for it. A failure is recorded in the store, except one that may pass
     * (section 4.4); that one is not tried again for [UNAVAILABLE_BACKOFF_MILLIS].
     */
    suspend fun thumbnail(request: ThumbRequest): ThumbResult = checkNotNull(resolve(request, Mode.Make))

    /**
     * サムネイルを作り直す: forgets the thumbnail and the failure of [request], including those of the images a
     * folder tried before the one it shows, and makes the thumbnail again.
     */
    suspend fun regenerate(request: ThumbRequest): ThumbResult = checkNotNull(resolve(request, Mode.Remake))

    /**
     * The result for [request], from the store when it can and made otherwise. It emits once, then waits: the
     * thumbnail stays held against eviction until the collector is cancelled, so collect it for as long as the
     * item is on screen and cancel it when the item leaves. Collecting only the first value releases the hold
     * at once.
     */
    fun observe(request: ThumbRequest): Flow<ThumbResult> = flow {
        val key = when (val subject = request.subject) {
            is ThumbSubject.Book -> keyOf(subject, request)
            is ThumbSubject.Folder -> keyOf(subject, request)
        }
        store.hold(key).use {
            emit(cached(request) ?: thumbnail(request))
            awaitCancellation()
        }
    }

    /** Cancels all work. */
    fun close() = scope.cancel()

    private enum class Mode { CacheOnly, Make, Remake }

    /** A store or a folder listing that throws is the thumbnail being unavailable for now, not a crash of the list. */
    private suspend fun resolve(request: ThumbRequest, mode: Mode): ThumbResult? = try {
        when (val subject = request.subject) {
            is ThumbSubject.Book -> book(subject, request, mode, Urgency(request.priority))
            is ThumbSubject.Folder -> folder(subject, request, mode)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        if (mode == Mode.CacheOnly) null else ThumbResult.Unavailable
    }

    private fun keyOf(book: ThumbSubject.Book, request: ThumbRequest) =
        ThumbnailKey(book.id, book.modified, book.byteSize, book.coverEntry, request.sizePx, request.direction)

    private fun keyOf(folder: ThumbSubject.Folder, request: ThumbRequest) =
        ThumbnailKey(folder.id, folder.modified, byteSize = 0, FOLDER_COVER, request.sizePx, request.direction)

    private suspend fun book(book: ThumbSubject.Book, request: ThumbRequest, mode: Mode, urgency: Urgency): ThumbResult? {
        val key = keyOf(book, request)
        if (mode == Mode.Remake) {
            store.remove(key)
            unavailableUntil.remove(key)
        }
        stored(key)?.let { return it }
        if (mode == Mode.CacheOnly) return null
        if (backingOff(key)) return ThumbResult.Unavailable
        return bookFlights.join(key, urgency) { make(book, key, it) }
    }

    private suspend fun stored(key: ThumbnailKey): ThumbResult? = when (val found = store.get(key)) {
        is StoredThumb.Hit -> ThumbResult.Ready(found.file, key)
        StoredThumb.Failed -> ThumbResult.Failed
        StoredThumb.Miss -> null
    }

    private suspend fun make(book: ThumbSubject.Book, key: ThumbnailKey, urgency: Urgency): ThumbResult =
        gates.getValue(book.lane).withPermit(urgency) {
            // Another flight for the key may have finished while this one waited for its turn.
            stored(key)?.let { return@withPermit it }
            try {
                when (val made = maker.make(book, key.sizePx, key.direction)) {
                    is Made.Webp -> ThumbResult.Ready(store.put(key, made.bytes), key)
                    Made.Unreadable -> {
                        store.markFailed(key)
                        ThumbResult.Failed
                    }
                    Made.Unavailable -> unavailable(key)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unavailable(key)
            }
        }

    private fun unavailable(key: ThumbnailKey): ThumbResult {
        backOff(key)
        return ThumbResult.Unavailable
    }

    private fun backingOff(key: ThumbnailKey): Boolean {
        val until = unavailableUntil[key] ?: return false
        if (now() < until) return true
        unavailableUntil.remove(key, until)
        return false
    }

    private fun backOff(key: ThumbnailKey) {
        if (unavailableUntil.size >= MAX_BACKOFF_ENTRIES) {
            val time = now()
            unavailableUntil.entries.removeIf { it.value <= time }
            if (unavailableUntil.size >= MAX_BACKOFF_ENTRIES) unavailableUntil.clear()
        }
        unavailableUntil[key] = now() + UNAVAILABLE_BACKOFF_MILLIS
    }

    /**
     * A search for the cover of a folder, and what it has seen so far: [found] is a copy of the first thumbnail it
     * got. Its books are made with the [urgency] of the folder.
     */
    private class Search(val request: ThumbRequest, val mode: Mode, val urgency: Urgency) {
        var found: ByteArray? = null
        var unavailable = false
        var exhausted = false
        var books = 0
        var listings = 0

        val done: Boolean get() = found != null || exhausted
    }

    /**
     * One search of the folder [key]. Each regeneration of the folder starts a new [generation], so it does not
     * join a search that began with an older request, and a search of an older generation stores nothing.
     */
    private data class FolderRun(val key: ThumbnailKey, val generation: Long)

    /** A folder is stored like a book, under a key of its own, so finding it never lists the folder. */
    private suspend fun folder(folder: ThumbSubject.Folder, request: ThumbRequest, mode: Mode): ThumbResult? {
        val key = keyOf(folder, request)
        val run = if (mode == Mode.Remake) {
            folderWrites.withLock {
                store.remove(key)
                FolderRun(key, folderGenerations.merge(key, 1L, Long::plus)!!)
            }
        } else {
            stored(key)?.let { return it }
            if (mode == Mode.CacheOnly) return null
            FolderRun(key, folderGenerations[key] ?: 0L)
        }
        // A search that a regeneration overtook answers with what the regeneration finds.
        return folderFlights.join(run, Urgency(request.priority)) { search(folder, run, Search(request, mode, it)) }
            ?: folder(folder, request, Mode.Make)
    }

    /**
     * Visits the chosen image and then the items in natural order, sub-folders in place, until one gives a
     * thumbnail, and stores a copy of it under the folder's key. A folder none of them gives one for is recorded
     * as failed when every attempt was final. `null` when a regeneration started after [run], which then stores
     * nothing.
     */
    private suspend fun search(folder: ThumbSubject.Folder, run: FolderRun, search: Search): ThumbResult? {
        // Another flight for the key may have stored it since [folder] looked.
        stored(run.key)?.let { return it }
        folder.chosen?.let { visit(it, search) }
        if (!search.done) {
            val entries = listing(search, FolderEntry.Directory(folder.id.value, folder.children))
            if (entries != null) walk(entries, depth = 0, search)
        }
        return folderWrites.withLock {
            val found = search.found
            when {
                (folderGenerations[run.key] ?: 0L) != run.generation -> null
                found != null -> ThumbResult.Ready(store.put(run.key, found), run.key)
                search.unavailable -> ThumbResult.Unavailable
                else -> {
                    store.markFailed(run.key)
                    ThumbResult.Failed
                }
            }
        }
    }

    private suspend fun walk(entries: List<FolderEntry>, depth: Int, search: Search) {
        for (entry in entries.sortedWith(compareBy(NaturalOrder) { it.name })) {
            when (entry) {
                is FolderEntry.Item -> visit(entry.book, search)
                is FolderEntry.Directory -> if (depth < MAX_SEARCH_DEPTH) {
                    listing(search, entry)?.let { walk(it, depth + 1, search) }
                } else {
                    search.exhausted = true
                }
            }
            if (search.done) return
        }
    }

    private suspend fun visit(book: ThumbSubject.Book, search: Search) {
        if (search.books == MAX_SEARCH_BOOKS) {
            search.exhausted = true
            return
        }
        search.books++
        // Held from before the store is asked until the copy is read, so no eviction falls in between.
        store.hold(keyOf(book, search.request)).use {
            when (val result = checkNotNull(book(book, search.request, search.mode, search.urgency))) {
                is ThumbResult.Ready -> search.found = withContext(io) { result.file.readBytes() }
                ThumbResult.Failed -> Unit
                ThumbResult.Unavailable -> search.unavailable = true
            }
        }
    }

    /** The entries of [directory], or `null` when the search has listed as many folders as it may or the folder cannot be read. */
    private suspend fun listing(search: Search, directory: FolderEntry.Directory): List<FolderEntry>? {
        if (++search.listings > MAX_SEARCH_LISTINGS) {
            search.exhausted = true
            return null
        }
        return try {
            withContext(io) { directory.children() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            search.unavailable = true
            null
        }
    }

    companion object {
        /** The most books a search for the cover of a folder tries. */
        const val MAX_SEARCH_BOOKS = 24

        /** The most folders a search for the cover of a folder lists, the folder itself included. */
        const val MAX_SEARCH_LISTINGS = 32

        /** How many folders deep a search for the cover of a folder goes. */
        const val MAX_SEARCH_DEPTH = 6

        /** How long a request for a thumbnail that failed for a passing reason answers [ThumbResult.Unavailable] without trying. */
        const val UNAVAILABLE_BACKOFF_MILLIS = 60_000L

        private const val MAX_BACKOFF_ENTRIES = 1024

        /** The `coverEntry` of the key that stands for a folder, which no page has. */
        private const val FOLDER_COVER = -2

        /** Thumbnails that decode with [decoding], a decoder of their own. Make one for the process. */
        fun create(store: ThumbnailStore, decoding: ImageDecoding): Thumbnails = Thumbnails(
            store,
            BookThumbnailMaker(decoding),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            Dispatchers.IO,
            now = { System.nanoTime() / 1_000_000 },
        )

        /**
         * Thumbnails sized for this device: half the memory budget of the reader, two decodes at once (one on a
         * device with little memory), and PDF support.
         */
        fun forDevice(context: Context, store: ThumbnailStore): Thumbnails {
            val budget = ImageDecoding.deviceBudget(context)
            val parallelism = if (budget < LOW_MEMORY_BYTES) 1 else ThumbLane.Local.parallelism
            return create(store, ImageDecoding(budget / 2, parallelism, context.getSystemService(StorageManager::class.java)))
        }

        private const val LOW_MEMORY_BYTES = 128L shl 20
    }
}
