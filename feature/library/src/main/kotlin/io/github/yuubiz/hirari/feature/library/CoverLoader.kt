package io.github.yuubiz.hirari.feature.library

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbRequest
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbResult
import io.github.yuubiz.hirari.core.ui.TraceSection
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import io.github.yuubiz.hirari.core.image.thumbnail.Thumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

/** What a row shows of its cover. */
sealed interface CoverState<out I> {
    /** Nothing yet: the thumbnail is being looked up or made. */
    data object Pending : CoverState<Nothing>

    data class Loaded<I>(val image: I) : CoverState<I>

    /** The cover cannot be made for this version of the file (docs/spec.md section 4.4): the placeholder stays. */
    data object Failed : CoverState<Nothing>

    /**
     * Reading failed for a reason that may pass; the placeholder shows until the next attempt after sixty seconds,
     * or until the connection of its place comes back.
     */
    data object Unavailable : CoverState<Nothing>
}

/** Decoded covers, least recently used out first once their bytes pass [maxBytes] (docs/spec.md section 5.4, メモリ). */
class MemoryImages<K : Any, I : Any>(private val maxBytes: Long, private val sizeOf: (I) -> Int) {
    private val entries = LinkedHashMap<K, I>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    operator fun get(key: K): I? = entries[key]

    @Synchronized
    fun put(key: K, image: I) {
        val size = sizeOf(image)
        if (size > maxBytes) return
        entries.put(key, image)?.let { bytes -= sizeOf(it) }
        bytes += size
        val oldest = entries.entries.iterator()
        while (bytes > maxBytes && oldest.hasNext()) {
            val eldest = oldest.next()
            bytes -= sizeOf(eldest.value)
            oldest.remove()
        }
    }

    @Synchronized
    fun remove(key: K) {
        entries.remove(key)?.let { bytes -= sizeOf(it) }
    }

    @get:Synchronized
    val size: Int get() = entries.size
}

/**
 * Turns the results of the thumbnail source into what a row draws. [show] is for a row on screen: its collection
 * is the hold on the thumbnail, so collect it for as long as the row is bound and cancel it when the row leaves. A
 * row whose cover is unavailable looks again each time its `recoveries` emit (docs/spec.md section 4.4).
 * Decoding the stored WebP runs on [decodeContext], never on the caller's thread, and the decoded image is kept in
 * [memory] so a row scrolled back into view needs neither the store nor a decode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverLoader<I : Any>(
    private val source: ThumbnailSource,
    private val memory: MemoryImages<CoverKey, I>,
    private val decodeContext: CoroutineContext,
    private val decode: (File) -> I?,
) {
    private val refreshed = MutableSharedFlow<CoverKey>(extraBufferCapacity = REFRESH_BUFFER)
    private val decodeSlots = Semaphore(2)
    private val nearbyDecode = Mutex()
    private val visibleDecodes = MutableStateFlow(0)

    /** The decoded cover of [key] if it is in memory. Cheap enough to ask while binding a row. */
    fun peek(key: CoverKey): I? = memory[key]

    fun show(key: CoverKey, request: ThumbRequest, recoveries: Flow<Unit> = emptyFlow()): Flow<CoverState<I>> = flow {
        val unavailable = AtomicBoolean(false)
        val fresh = refreshed.filter { it == key }.map { false }.onStart { emit(false) }
        val again = recoveries.filter { unavailable.get() }.map { true }
        coroutineScope {
            val timed = MutableSharedFlow<Boolean>()
            var timer: Job? = null
            try {
                emitAll(merge(fresh, again, timed).flatMapLatest { look(key, request, retry = it) }.onEach {
                    unavailable.set(it == CoverState.Unavailable)
                    timer?.cancel()
                    if (it == CoverState.Unavailable) timer = launch {
                        delay(Thumbnails.UNAVAILABLE_BACKOFF_MILLIS)
                        // A timed request respects the source's back-off; a reconnection bypasses it.
                        timed.emit(false)
                    }
                })
            } finally {
                timer?.cancel()
            }
        }
    }

    /** Store only: holds and decodes a nearby cover, behind visible decodes, until cancelled. */
    fun warm(key: CoverKey, request: ThumbRequest): Flow<ThumbResult> = flow {
        source.hold(request).use {
            if (memory[key] == null) {
                val result = withContext(decodeContext) { source.cached(request) }
                if (result is ThumbResult.Ready) nearbyDecode.withLock {
                    // At most one nearby decode runs. Visible work takes the next free slot first.
                    var done = false
                    while (!done) {
                        visibleDecodes.first { it == 0 }
                        decodeSlots.withPermit {
                            if (visibleDecodes.value == 0) {
                                if (memory[key] == null) loaded(key, result.file)
                                done = true
                            }
                        }
                    }
                }
                result?.let { emit(it) }
            }
            awaitCancellation()
        }
    }

    /** The thumbnail of [key] changed in the store: forgets the decoded one and makes every [show] of it look again. */
    fun refresh(key: CoverKey) {
        memory.remove(key)
        refreshed.tryEmit(key)
    }

    private fun look(key: CoverKey, request: ThumbRequest, retry: Boolean): Flow<CoverState<I>> = flow {
        val trace = TraceSection.begin(TRACE_THUMBNAIL)
        try {
            memory[key]?.let {
                source.hold(request).use { _ ->
                    trace.end()
                    emit(CoverState.Loaded(it))
                    awaitCancellation()
                }
            }
            (if (retry) source.retry(request) else source.observe(request)).collect { result ->
                val state = when (result) {
                    is ThumbResult.Ready -> {
                        visibleDecodes.update { it + 1 }
                        try {
                            decodeSlots.withPermit { loaded(key, result.file) }
                        } finally {
                            visibleDecodes.update { it - 1 }
                        }
                    }
                    ThumbResult.Failed -> CoverState.Failed
                    ThumbResult.Unavailable -> CoverState.Unavailable
                }
                trace.end()
                emit(state)
            }
        } finally {
            trace.end()
        }
    }

    private suspend fun loaded(key: CoverKey, file: File): CoverState<I> {
        val image = withContext(decodeContext) { decode(file) } ?: return CoverState.Unavailable
        memory.put(key, image)
        return CoverState.Loaded(image)
    }

    private companion object {
        const val REFRESH_BUFFER = 64
    }
}

/** The trace section of docs/spec.md section 5.2, P8: a bound row from the lookup of its cover to the cover or its failure. */
const val TRACE_THUMBNAIL = "hirari.thumbnail"

/**
 * Where one list stands (docs/spec.md section 4.4): the cover of each of its items and the items on screen. The
 * list [update]s it as it scrolls, and the lanes read [distance] each time they pick the next cover, so the covers
 * nearest the middle of the screen are made first wherever the list has scrolled to.
 */
class CoverFocus {
    private val states = MutableStateFlow<Map<CoverKey, CoverState<ImageBitmap>>>(emptyMap())

    fun show(key: CoverKey): Flow<CoverState<ImageBitmap>> =
        states.map { it[key] ?: CoverState.Pending }.distinctUntilChanged()

    fun publish(key: CoverKey, state: CoverState<ImageBitmap>) {
        states.value = states.value + (key to state)
    }

    fun retain(keys: Set<CoverKey>) {
        states.value = states.value.filterKeys { it in keys }
    }

    @Volatile
    private var positions: Map<CoverKey, Int> = emptyMap()

    @Volatile
    private var refs: List<CoverRef?> = emptyList()

    /** The first plus the last index on screen: twice the middle, which keeps distances whole. */
    @Volatile
    private var middleTwice = 0

    fun update(refs: List<CoverRef?>, visible: IntRange) {
        if (refs !== this.refs) {
            positions = refs.withIndex().mapNotNull { (index, ref) -> ref?.let { it.key to index } }.toMap()
            this.refs = refs
        }
        if (!visible.isEmpty()) middleTwice = visible.first + visible.last
    }

    /** How far the item of [key] is from the middle of the screen, in halves of an item; the farthest for one not in the list. */
    fun distance(key: CoverKey): Int = positions[key]?.let { abs(2 * it - middleTwice) } ?: Int.MAX_VALUE
}

/**
 * The items the list prepares ahead (docs/spec.md section 4.4): [update] starts what is newly wanted and cancels
 * what no longer is, and leaves the rest running, so a list scrolling steadily does not restart the work it has
 * under way. [start] begins the preparation of one item and returns its job.
 */
class Prefetcher<K : Any>(private val start: (K) -> Job) {
    private val running = HashMap<K, Job>()

    val wanted: Set<K> get() = running.keys

    fun update(next: Set<K>) {
        val gone = running.keys - next
        for (key in gone) running.remove(key)?.cancel()
        for (key in next) if (key !in running) running[key] = start(key)
    }

    fun clear() = update(emptySet())

    companion object {
        fun forRefs(scope: CoroutineScope, prepare: suspend (CoverRef) -> Unit) = Prefetcher<CoverRef> { ref -> scope.launch { prepare(ref) } }
    }
}

/**
 * The indices of the items to prepare around the visible [first]..[last] of [total]: two screenfuls on either side,
 * at least [MIN_MARGIN] and at most [MAX_MARGIN] items, and the visible items themselves.
 */
fun nearbyWindow(first: Int, last: Int, total: Int): IntRange {
    if (total <= 0 || last < first) return IntRange.EMPTY
    val margin = ((last.toLong() - first + 1) * 2).coerceIn(MIN_MARGIN.toLong(), MAX_MARGIN.toLong()).toInt()
    return (first - margin).coerceAtLeast(0)..(last + margin).coerceAtMost(total - 1)
}

const val MIN_MARGIN = 12
const val MAX_MARGIN = 96

/** Owns cover work for a viewport; composing a row alone never starts generation. */
class CoverViewport(
    private val focus: CoverFocus,
    private val visible: Prefetcher<CoverRef>,
    private val nearby: Prefetcher<CoverRef>,
) {
    fun update(refs: List<CoverRef?>, range: IntRange, scrolling: Boolean) {
        focus.update(refs, range)
        val shown = range.filter { it in refs.indices }.mapNotNull { refs[it] }
            .sortedBy { focus.distance(it.key) }.toSet()
        visible.update(if (scrolling) shown.intersect(visible.wanted) else shown)
        focus.retain(shown.mapTo(HashSet()) { it.key })
        val window = nearbyWindow(range.first, range.last, refs.size)
        nearby.update(window.filter { it !in range }.mapNotNullTo(HashSet()) { refs[it] })
    }

    fun clear() {
        visible.clear()
        nearby.clear()
        focus.retain(emptySet())
    }
}
