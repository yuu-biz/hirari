package io.github.yuubiz.hirari.core.image.thumbnail

import android.content.Context
import android.os.storage.StorageManager
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.model.NaturalOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Thumbnails for the lists of the library (docs/spec.md sections 4.4 and 5.4): finds one in the [ThumbnailStore],
 * and otherwise makes it from the cover page, hands it over at once and stores it afterwards ([Saves]).
 *
 * The work is not tied to the caller. Each [ThumbLane] makes as many thumbnails at once as [setParallelism]
 * allows, on a decode dispatcher of their own. A request that asks for a thumbnail already being made
 * waits for that one. Cancelling a request, as a list does when the item scrolls out, stops the work once nobody
 * else waits for it. Visible requests are served before nearby ones.
 *
 * Two passes serve a list (section 4.4). [cached] only looks in the store, and is cheap: call it for every
 * visible item first. [thumbnail] also makes what is missing. [observe] does both for one item and holds the
 * thumbnail in the store for as long as the item is shown.
 *
 * Each thumbnail is recorded in the store while it is made. When the process ended making one, the next process
 * skips that one: it stays unavailable in that process, and the process after it makes it again. When the system
 * says that process crashed or stopped responding ([previousExit]), the next process makes none at all until
 * [resume] ([held]): only what the store has shows, and the rest is unavailable. A regeneration still makes its own.
 *
 * Every function is safe to call from the main thread.
 */
class Thumbnails internal constructor(
    private val store: ThumbnailStore,
    private val maker: ThumbnailMaker,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
    private val previewer: PagePreviewer = PagePreviewer { _, _, _ -> PagePreview.Unavailable },
    private val previousExit: () -> PreviousExit = { PreviousExit.Unknown },
    private val deviceBudgetBytes: Long = 128L shl 20,
    private val updateDecoding: (Long, Int) -> Unit = { _, _ -> },
) {
    private val bookFlights = Flights<ThumbnailKey, ThumbResult>(scope)
    private val saves = Saves(store, scope)
    private val folderFlights = HashMap<ThumbnailKey, FolderFlight>()
    private val folderWrites = Mutex()
    private var parallelism = ThumbnailParallelism.effective(2, deviceBudgetBytes)
    private val gates = ThumbLane.entries.associateWith { PriorityGate(parallelism) }

    @Synchronized
    fun setParallelism(chosen: Int) {
        val n = ThumbnailParallelism.effective(chosen, deviceBudgetBytes)
        if (n == parallelism) return
        parallelism = n
        updateDecoding(ThumbnailParallelism.perDecodeBudget(deviceBudgetBytes, n), n)
        gates.values.forEach { it.setPermits(n) }
    }

    @Volatile
    private var following: Deferred<Unit>? = null

    /**
     * Applies each count of [counts] with [setParallelism], starting at the first thumbnail or preview asked for, which
     * waits for the first count. Not at once: the start of the app must be the first to read the stored settings (it
     * reports a file it cannot read), and the library is built before it.
     */
    fun followParallelism(counts: Flow<Int>) {
        following = scope.async(start = CoroutineStart.LAZY) {
            val first = CompletableDeferred<Unit>()
            scope.launch {
                try {
                    counts.distinctUntilChanged().collect {
                        setParallelism(it)
                        first.complete(Unit)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The counts stop; the last applied one stays.
                } finally {
                    first.complete(Unit)
                }
            }
            first.await()
        }
    }

    private suspend fun gate(lane: ThumbLane): PriorityGate {
        following?.await()
        return gates.getValue(lane)
    }

    /** One listing at a time per lane for the searches for folder covers, beside the covers being made (section 4.4). */
    private val listingGates = ThumbLane.entries.associateWith { PriorityGate(1) }
    private val unavailableUntil = ConcurrentHashMap<ThumbnailKey, Long>()
    private val skipped: MutableSet<ThumbnailKey> = ConcurrentHashMap.newKeySet()
    private val making = MutableStateFlow(Making.Unknown)
    private val resumed = MutableStateFlow(false)

    private enum class Making { Unknown, Allowed, Held }

    init {
        scope.launch {
            val held = try {
                store.settleInterrupted() && withContext(io) { previousExit() } == PreviousExit.Crashed
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            making.compareAndSet(Making.Unknown, if (held) Making.Held else Making.Allowed)
        }
    }

    /** Whether thumbnails are not made, since the last process crashed making one (docs/spec.md section 4.4). */
    val held: Flow<Boolean> = making.map { it == Making.Held }.distinctUntilChanged()

    /** Retains that [resume] allowed making covers, including for collectors that start later. */
    val resumptions: Flow<Unit> = resumed.filter { it }.map { Unit }

    /** 表紙を作る: makes thumbnails again after [held]. */
    fun resume() {
        if (making.value == Making.Held) {
            scope.launch {
                store.clearInterrupted()
                unavailableUntil.clear()
                if (making.compareAndSet(Making.Held, Making.Allowed)) resumed.value = true
            }
        }
    }

    private suspend fun isHeld(): Boolean = making.first { it != Making.Unknown } == Making.Held

    /**
     * Pass 1: the thumbnail for [request] if the store has it or knows it failed, and `null` if it has to be made.
     * It does not list a folder: a folder has a thumbnail once pass 2 has searched it. It writes to the store only to
     * drop the mark of a thumbnail the last process left unfinished, which this process then skips.
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
     * folder tried before the one it shows, and makes the thumbnail again. A book's thumbnail being made meanwhile is
     * stopped, not joined, and what it made is not stored: it may come from what the book was before (section 4.4).
     * Everyone waiting for it gets the thumbnail made again.
     */
    suspend fun regenerate(request: ThumbRequest): ThumbResult = checkNotNull(resolve(request, Mode.Remake))

    /**
     * The page with the `entryIndex` [entryIndex] of [book] shown whole and fitted into [maxSidePx], for choosing a
     * part of it as the cover (section 4.4). It is not stored. It takes a turn of the lane like a thumbnail does, ahead
     * of the ones a list only prepares, and the caller owns the bitmap. The book is opened and the page decoded on the
     * IO dispatcher, whatever thread asks.
     */
    suspend fun preview(book: ThumbSubject.Book, entryIndex: Int, maxSidePx: Int): PagePreview = try {
        gate(book.lane).withPermit(Urgency(ThumbPriority.Visible)) { previewOnIo(book, entryIndex, maxSidePx) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        PagePreview.Unavailable
    }

    /** A bitmap made when the caller was cancelled has no owner left, so it is recycled here. */
    private suspend fun previewOnIo(book: ThumbSubject.Book, entryIndex: Int, maxSidePx: Int): PagePreview {
        var made: PagePreview? = null
        try {
            return withContext(io) { previewer.preview(book, entryIndex, maxSidePx).also { made = it } }
        } catch (e: CancellationException) {
            (made as? PagePreview.Ready)?.bitmap?.recycle()
            throw e
        }
    }

    /**
     * The result for [request], from the store when it can and made otherwise. It emits once, then waits: the
     * thumbnail stays held against eviction until the collector is cancelled, so collect it for as long as the
     * item is on screen and cancel it when the item leaves. Collecting only the first value releases the hold
     * at once.
     */
    fun observe(request: ThumbRequest): Flow<ThumbResult> = watch(request, Mode.Make)

    /**
     * [observe] once the reason a thumbnail could not be made has passed, as when the connection of its place came back
     * (docs/spec.md section 4.4): the books whose last try failed for a reason that may pass are tried at once, not
     * after [UNAVAILABLE_BACKOFF_MILLIS]. One that fails again backs off as before.
     */
    fun retry(request: ThumbRequest): Flow<ThumbResult> = watch(request, Mode.Retry)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watch(request: ThumbRequest, mode: Mode): Flow<ThumbResult> = flow {
        hold(request).use {
            var wasHeld = false
            emitAll(making.filter { it != Making.Unknown }.transformLatest { state ->
                val next = if (wasHeld && state == Making.Allowed) Mode.Retry else mode
                wasHeld = state == Making.Held
                emit(cached(request) ?: checkNotNull(resolve(request, next)))
                awaitCancellation()
            })
        }
    }

    /**
     * Holds the thumbnail of [request] against eviction until the result is closed, as [observe] does, but looks
     * at nothing: for an item shown from the image a list already decoded.
     */
    fun hold(request: ThumbRequest): Closeable = store.hold(
        when (val subject = request.subject) {
            is ThumbSubject.Book -> keyOf(subject, request)
            is ThumbSubject.Folder -> keyOf(subject, request)
        },
    )

    /** Cancels all work. */
    fun close() = scope.cancel()

    /** [Retry] makes what is missing as [Make] does, without waiting out the back-off of a failure that may pass. */
    private enum class Mode { CacheOnly, Make, Retry, Remake }

    /** A store or a folder listing that throws is the thumbnail being unavailable for now, not a crash of the list. */
    private suspend fun resolve(request: ThumbRequest, mode: Mode): ThumbResult? = try {
        when (val subject = request.subject) {
            is ThumbSubject.Book -> book(subject, request, mode, Urgency(request.priority, request.distance))
            is ThumbSubject.Folder -> folder(subject, request, mode)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        if (mode == Mode.CacheOnly) null else ThumbResult.Unavailable
    }

    private fun keyOf(book: ThumbSubject.Book, request: ThumbRequest) =
        ThumbnailKey(book.id, book.modified, book.byteSize, book.coverEntry, request.sizePx, request.direction, book.coverCrop)

    private fun keyOf(folder: ThumbSubject.Folder, request: ThumbRequest) =
        ThumbnailKey(folder.id, folder.modified, byteSize = 0, FOLDER_COVER, request.sizePx, request.direction)

    private suspend fun book(book: ThumbSubject.Book, request: ThumbRequest, mode: Mode, urgency: Urgency): ThumbResult? {
        val key = keyOf(book, request)
        if (mode == Mode.Remake) {
            saves.forget(key)
            skipped.remove(key)
            unavailableUntil.remove(key)
            // What the replaced make saved before it ended goes too.
            return bookFlights.replace(key, urgency) {
                saves.forget(key)
                make(book, key, it)
            }
        }
        if (mode == Mode.Retry) unavailableUntil.remove(key)
        if (key in skipped) return ThumbResult.Unavailable
        known(key)?.let { return it }
        if (mode == Mode.CacheOnly) return null
        if (isHeld()) return ThumbResult.Unavailable
        if (backingOff(key)) return ThumbResult.Unavailable
        return bookFlights.join(key, urgency) { make(book, key, it) }
    }

    /**
     * A book's thumbnail made and not stored yet, or what the store knows. The store is asked second: a save writes the
     * store before it lets the thumbnail go, so one of the two has it.
     */
    private suspend fun known(key: ThumbnailKey): ThumbResult? =
        saves.pending(key)?.let { ThumbResult.Ready(ThumbImage.Fresh(it), key) } ?: stored(key)

    private suspend fun stored(key: ThumbnailKey): ThumbResult? = when (val found = store.get(key)) {
        is StoredThumb.Hit -> ThumbResult.Ready(ThumbImage.Stored(found.file), key)
        StoredThumb.Failed -> ThumbResult.Failed
        StoredThumb.Interrupted -> if (isHeld()) ThumbResult.Failed else skip(key)
        StoredThumb.Miss -> null
    }

    /**
     * A thumbnail the last process was making when it ended is not made in this process, so a book that ends the
     * process cannot end every one, and its mark goes so that the next process makes it (section 4.4).
     */
    private suspend fun skip(key: ThumbnailKey): ThumbResult {
        skipped += key
        store.remove(key)
        return ThumbResult.Unavailable
    }

    /** What a flight came to in its turn of the lane: an outcome, or a WebP to store once the turn is given back. */
    private sealed interface Turn {
        class Settled(val result: ThumbResult) : Turn

        class Encoded(val webp: ByteArray) : Turn
    }

    /** The thumbnail goes to everyone waiting before it is stored, and the save waits for no turn (section 4.4). */
    private suspend fun make(book: ThumbSubject.Book, key: ThumbnailKey, urgency: Urgency): ThumbResult {
        val turn = gate(book.lane).withPermit(urgency) {
            // Another flight for the key may have finished while this one waited for its turn.
            known(key)?.let { return@withPermit Turn.Settled(it) }
            try {
                when (val made = store.making(key) { timed(book.lane) { maker.make(book, key.sizePx, key.direction) } }) {
                    is Made.Webp -> Turn.Encoded(made.bytes)
                    Made.Unreadable -> {
                        store.markFailed(key)
                        Turn.Settled(ThumbResult.Failed)
                    }
                    Made.Unavailable -> Turn.Settled(unavailable(key))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Turn.Settled(unavailable(key))
            }
        }
        return when (turn) {
            is Turn.Settled -> turn.result
            is Turn.Encoded -> {
                saves.add(key, turn.webp)
                ThumbResult.Ready(ThumbImage.Fresh(turn.webp), key)
            }
        }
    }

    /** A cover that takes longer than its lane allows gives the lane up and counts as one that may be made later. */
    private suspend fun timed(lane: ThumbLane, make: suspend () -> Made): Made {
        val limit = lane.limitMillis ?: return make()
        return withTimeoutOrNull(limit) { make() } ?: Made.Unavailable
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
     * got. Its books are made and its folders listed with the [urgency] of the folder, each in a turn: a book in one of
     * the folder's lane, a listing in the one turn for listings of that lane. The first failure that may pass ends it ([unavailable]): the next book would most likely fail the same
     * way, and trying it would only make one cover cost many. A book skipped because the last process ended while
     * making it ([skipped]) is passed over, but the folder is not recorded as failed for it.
     */
    private class Search(val request: ThumbRequest, val mode: Mode, val urgency: Urgency, val lane: ThumbLane) {
        var found: ByteArray? = null
        var unavailable = false
        var skipped = false
        var exhausted = false
        var books = 0
        var listings = 0

        val done: Boolean get() = found != null || unavailable || exhausted
    }

    /**
     * Everyone waiting for the cover of one folder, and the [search] that answers them. A regeneration puts a
     * search of its own in place of the one running, so everyone waiting gets what the new one finds and the
     * replaced one stores nothing. Guarded by [folderFlights].
     */
    private class FolderFlight(val urgency: Urgency) {
        val result = CompletableDeferred<ThumbResult>()
        lateinit var search: Job
        var waiters = 0
    }

    /** A folder is stored like a book, under a key of its own, so finding it never lists the folder. */
    private suspend fun folder(folder: ThumbSubject.Folder, request: ThumbRequest, mode: Mode): ThumbResult? {
        val key = keyOf(folder, request)
        val urgency = Urgency(request.priority, request.distance)
        val flight = if (mode == Mode.Remake) {
            folderWrites.withLock {
                store.remove(key)
                board(folder, key, request, mode, urgency)
            }
        } else {
            stored(key)?.let { return it }
            if (mode == Mode.CacheOnly) return null
            if (isHeld()) return ThumbResult.Unavailable
            board(folder, key, request, mode, urgency)
        }
        try {
            return flight.result.await()
        } finally {
            leave(key, flight, urgency)
        }
    }

    private fun board(folder: ThumbSubject.Folder, key: ThumbnailKey, request: ThumbRequest, mode: Mode, urgency: Urgency): FolderFlight =
        synchronized(folderFlights) {
            val running = folderFlights[key]
            val flight = running ?: FolderFlight(Urgency.borrowed()).also { folderFlights[key] = it }
            flight.urgency.follow(urgency)
            if (running == null || mode == Mode.Remake) {
                val replaced = running?.search
                fly(folder, key, flight, Search(request, mode, flight.urgency, folder.lane))
                // Cancelled once replaced: a search that ends inside cancel(), as on an immediate dispatcher, settles nothing.
                replaced?.cancel()
            }
            flight.waiters++
            flight
        }

    private fun fly(folder: ThumbSubject.Folder, key: ThumbnailKey, flight: FolderFlight, search: Search) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                search(folder, key, search)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ThumbResult.Unavailable
            }
            settle(key, flight, currentCoroutineContext().job, Result.success(result))
        }
        flight.search = job
        job.invokeOnCompletion { cause -> if (cause != null) settle(key, flight, job, Result.failure(cause)) }
        job.start()
    }

    /**
     * Answers everyone waiting for [flight], unless a regeneration replaced [search] with another. The answer goes
     * out of the lock, since a waiter on an immediate dispatcher resumes inside it.
     */
    private fun settle(key: ThumbnailKey, flight: FolderFlight, search: Job, outcome: Result<ThumbResult>) {
        synchronized(folderFlights) {
            if (flight.search !== search) return
            folderFlights.remove(key, flight)
        }
        flight.result.completeWith(outcome)
    }

    private fun leave(key: ThumbnailKey, flight: FolderFlight, urgency: Urgency) {
        synchronized(folderFlights) {
            flight.urgency.unfollow(urgency)
            if (--flight.waiters == 0) {
                folderFlights.remove(key, flight)
                flight.search.cancel()
            }
        }
    }

    /**
     * Visits the chosen image, then the books of the folder in natural order, then its sub-folders in natural order,
     * until one gives a thumbnail, and stores a copy of it under the folder's [key]. A folder none of them gives one
     * for is recorded as failed when every attempt was final.
     */
    private suspend fun search(folder: ThumbSubject.Folder, key: ThumbnailKey, search: Search): ThumbResult {
        // Another flight for the key may have stored it since [folder] looked.
        stored(key)?.let { return it }
        withContext(io) { folder.resolveChosen() }?.let { visit(it, search) }
        if (!search.done) {
            val entries = listing(search, FolderEntry.Directory(folder.id.value, folder.children))
            if (entries != null) walk(entries, depth = 0, search)
        }
        return folderWrites.withLock {
            // A regeneration cancels the search it replaces under this lock, so that search stores nothing.
            currentCoroutineContext().ensureActive()
            val found = search.found
            when {
                found != null -> ThumbResult.Ready(ThumbImage.Stored(store.put(key, found)), key)
                search.unavailable || search.skipped -> ThumbResult.Unavailable
                else -> {
                    store.markFailed(key)
                    ThumbResult.Failed
                }
            }
        }
    }

    private suspend fun walk(entries: List<FolderEntry>, depth: Int, search: Search) {
        val (books, folders) = entries.sortedWith(compareBy(NaturalOrder) { it.name }).partition { it is FolderEntry.Item }
        for (entry in books + folders) {
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
        val key = keyOf(book, search.request)
        store.hold(key).use {
            when (val result = checkNotNull(book(book, search.request, search.mode, search.urgency))) {
                is ThumbResult.Ready -> search.found = when (val image = result.image) {
                    is ThumbImage.Stored -> withContext(io) { image.file.readBytes() }
                    is ThumbImage.Fresh -> image.webp
                }
                ThumbResult.Failed -> Unit
                ThumbResult.Unavailable -> if (key in skipped) search.skipped = true else search.unavailable = true
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
            listingGates.getValue(search.lane).withPermit(search.urgency) { withContext(io) { directory.children() } }
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

        /** The longest one cover over SMB may take once it has its turn (docs/spec.md section 4.4). */
        const val SMB_COVER_LIMIT_MILLIS = 10_000L

        /** How long a request for a thumbnail that failed for a passing reason answers [ThumbResult.Unavailable] without trying. */
        const val UNAVAILABLE_BACKOFF_MILLIS = 60_000L

        private const val MAX_BACKOFF_ENTRIES = 1024

        /** The `coverEntry` of the key that stands for a folder, which no page has. */
        private const val FOLDER_COVER = -2

        /**
         * Thumbnails that decode with [decoding], a decoder of their own, and ask [previousExit] whether the last
         * process crashed. Make one for the process.
         */
        fun create(store: ThumbnailStore, decoding: ImageDecoding, previousExit: () -> PreviousExit): Thumbnails =
            create(store, decoding, decoding.budgetBytes * 2, previousExit)

        private fun create(
            store: ThumbnailStore,
            decoding: ImageDecoding,
            deviceBudgetBytes: Long,
            previousExit: () -> PreviousExit,
        ): Thumbnails {
            val maker = BookThumbnailMaker(decoding)
            return Thumbnails(
                store,
                maker,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                Dispatchers.IO,
                now = { System.nanoTime() / 1_000_000 },
                previewer = maker,
                previousExit = previousExit,
                deviceBudgetBytes = deviceBudgetBytes,
                updateDecoding = { budget, n -> maker.decoding = ImageDecoding(budget, n, decoding.storage) },
            )
        }

        /**
         * Thumbnails sized for this device: half the memory budget of the reader, initially two decodes at once (one on a
         * device with little memory), and PDF support.
         */
        fun forDevice(context: Context, store: ThumbnailStore): Thumbnails {
            val budget = ImageDecoding.deviceBudget(context)
            val parallelism = ThumbnailParallelism.effective(2, budget)
            val decoding = ImageDecoding(budget / 2, parallelism, context.getSystemService(StorageManager::class.java))
            return create(store, decoding, budget) { previousExit(context) }
        }
    }
}
