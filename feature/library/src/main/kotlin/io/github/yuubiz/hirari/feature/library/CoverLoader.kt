package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.image.thumbnail.ThumbRequest
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbResult
import io.github.yuubiz.hirari.core.ui.TraceSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

/** What a row shows of its cover. */
sealed interface CoverState<out I> {
    /** Nothing yet: the thumbnail is being looked up or made. */
    data object Pending : CoverState<Nothing>

    data class Loaded<I>(val image: I) : CoverState<I>

    /** The cover cannot be made for this version of the file (docs/spec.md section 4.4): the placeholder stays. */
    data object Failed : CoverState<Nothing>

    /** Reading failed for a reason that may pass; the placeholder shows until the row is bound again. */
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
 * is the hold on the thumbnail, so collect it for as long as the row is bound and cancel it when the row leaves.
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

    /** The decoded cover of [key] if it is in memory. Cheap enough to ask while binding a row. */
    fun peek(key: CoverKey): I? = memory[key]

    fun show(key: CoverKey, request: ThumbRequest): Flow<CoverState<I>> =
        refreshed.filter { it == key }.onStart { emit(key) }.flatMapLatest { look(key, request) }

    /** Keeps the thumbnail of [request] in the store and held, for an item the list will show soon. Nothing is decoded. */
    fun warm(request: ThumbRequest): Flow<ThumbResult> = source.observe(request)

    /** The thumbnail of [key] changed in the store: forgets the decoded one and makes every [show] of it look again. */
    fun refresh(key: CoverKey) {
        memory.remove(key)
        refreshed.tryEmit(key)
    }

    private fun look(key: CoverKey, request: ThumbRequest): Flow<CoverState<I>> = flow {
        val trace = TraceSection.begin(TRACE_THUMBNAIL)
        try {
            memory[key]?.let {
                source.hold(request).use { _ ->
                    trace.end()
                    emit(CoverState.Loaded(it))
                    awaitCancellation()
                }
            }
            source.observe(request).collect { result ->
                val state = when (result) {
                    is ThumbResult.Ready -> loaded(key, result.file)
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
 * The indices of the items to prepare around the visible [first]..[last] of [total]: one screenful on either side,
 * at least [MIN_MARGIN] and at most [MAX_MARGIN] items, and the visible items themselves.
 */
fun nearbyWindow(first: Int, last: Int, total: Int): IntRange {
    if (total <= 0 || last < first) return IntRange.EMPTY
    val margin = (last - first + 1).coerceIn(MIN_MARGIN, MAX_MARGIN)
    return (first - margin).coerceAtLeast(0)..(last + margin).coerceAtMost(total - 1)
}

const val MIN_MARGIN = 6
const val MAX_MARGIN = 48
