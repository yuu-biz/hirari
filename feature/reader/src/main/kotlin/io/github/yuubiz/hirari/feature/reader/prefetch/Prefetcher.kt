package io.github.yuubiz.hirari.feature.reader.prefetch

import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.feature.reader.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The shape a page whose header could not be read takes in the layout: a portrait page, so it pairs like one and
 * its error card gets a slot of a usual page size.
 */
val FAILED_PAGE_SHAPE = PageShape.Known(1000, 1414)

/** One page of a prepared view: its pixels, or why it has none. */
sealed interface PreparedPage<out T> {
    data class Image<out T>(val image: T) : PreparedPage<T>
    data class Error(val result: PageResult<Nothing>) : PreparedPage<Nothing>
}

/**
 * The pixel size of each page of [view] in reading order, and for a half the side of its page it shows. A view
 * prepared for other sizes, before the screen turned or the scale mode changed, is stale.
 */
data class ViewTarget(val view: PageView, val sizes: List<PixelSize>, val crop: Side? = null) {
    val bytes: Long get() = sizes.sumOf { it.width.toLong() * it.height * 4 }
}

class PreparedView<out T>(val target: ViewTarget, val pages: List<PreparedPage<T>>)

/** Reads the pages of an open book. Each call returns a result and throws nothing but cancellation. */
interface PageLoader<T> {
    suspend fun shape(page: Int): PageResult<PageShape.Known>

    /** The pixels of [page] at [size]. With [crop], [size] is that half of the page. */
    suspend fun prepare(page: Int, size: PixelSize, crop: Side?): PageResult<T>
}

/** What the reader shows, for the next plan. [targets] gives `null` for a view whose shapes are not known. */
class PrefetchInput(
    val views: List<PageView>,
    val current: Int,
    val shapes: List<PageShape>,
    val travel: Turn,
    val targets: (PageView) -> ViewTarget?,
    /** Every view on screen, including both views of a turn. None of them is evicted. */
    val shown: Set<PageView>,
    val paused: Boolean = false,
)

/**
 * The per-book prefetch executor of docs/spec.md sections 5.3 and 8.3. Each [update] makes a new plan, cancels
 * the running jobs the plan no longer wants, starts the ones it adds, and evicts what it no longer keeps. Every
 * call and every job's bookkeeping runs in [scope], which is confined to one thread; the loader moves its work
 * off it. Raw bytes are not a layer of their own yet, so [PrefetchJob.ReadRaw] jobs do nothing.
 */
class Prefetcher<T>(
    private val scope: CoroutineScope,
    private val loader: PageLoader<T>,
    private val release: (T) -> Unit,
    private val budgetBytes: Long,
    private val depths: PrefetchDepths,
    private val listener: Listener,
) {
    interface Listener {
        /** A shape became known. A page whose header failed gets [FAILED_PAGE_SHAPE]. */
        fun onShape(page: Int, shape: PageShape.Known)

        /** A view was prepared or dropped, or a page failed. */
        fun onChanged()
    }

    private val cache = HashMap<PageView, PreparedView<T>>()
    private val running = HashMap<Any, Job>()
    private val failures = HashMap<Int, PageResult<Nothing>>()
    private val failedShapes = HashSet<Int>()
    private var last: PrefetchInput? = null

    val failedPages: Map<Int, PageResult<Nothing>> get() = failures

    /** The prepared [view], even when it is stale, so a view keeps drawing until its replacement is ready. */
    operator fun get(view: PageView): PreparedView<T>? = cache[view]

    fun update(input: PrefetchInput) {
        last = input
        val resident = Resident(views = cache.filter { (view, prepared) -> prepared.target == input.targets(view) }.keys)
        val plan = planPrefetch(
            PrefetchRequest(
                views = input.views,
                current = input.current,
                shapes = input.shapes,
                travel = input.travel,
                budgetBytes = budgetBytes,
                viewBytes = { input.targets(it)?.bytes ?: 0L },
                rawBytes = { 0L },
                resident = resident,
                depths = depths,
                paused = input.paused,
            ),
        )
        val dropped = cache.keys.filter { it !in plan.keep.views && it !in input.shown }
        dropped.forEach(::evict)
        val wanted = LinkedHashMap<Any, () -> Job>()
        for (job in plan.jobs) {
            when (job) {
                is PrefetchJob.ReadShape -> wanted[job] = { launchShape(job.page) }
                is PrefetchJob.PrepareView -> input.targets(job.view)?.let { target -> wanted[target] = { launchPrepare(target) } }
                is PrefetchJob.ReadRaw -> Unit
            }
        }
        for ((key, job) in running.entries.toList()) {
            if (key !in wanted) {
                job.cancel()
                running.remove(key)
            }
        }
        for ((key, start) in wanted) {
            if (key !in running) running[key] = start()
        }
        if (dropped.isNotEmpty()) listener.onChanged()
    }

    /** `TRIM_MEMORY_RUNNING_LOW` and above: everything but the views on screen goes. */
    fun trim() {
        val shown = last?.shown.orEmpty()
        val dropped = cache.keys.filter { it !in shown }
        dropped.forEach(::evict)
        if (dropped.isNotEmpty()) listener.onChanged()
    }

    /** Reads [page] again: its shape if that failed, then the views that hold it. */
    fun retry(page: Int) {
        failures.remove(page) ?: return
        cache.keys.filter { page in it.pages }.forEach(::evict)
        if (failedShapes.remove(page)) running[PrefetchJob.ReadShape(page)] = launchShape(page)
        listener.onChanged()
        last?.let(::update)
    }

    /** Cancels every job and releases every view. */
    fun close() {
        running.values.forEach { it.cancel() }
        running.clear()
        cache.keys.toList().forEach(::evict)
    }

    private fun evict(view: PageView) {
        cache.remove(view)?.pages?.forEach { if (it is PreparedPage.Image) release(it.image) }
    }

    private fun launchShape(page: Int): Job = scope.launch {
        val result = loader.shape(page)
        running.remove(PrefetchJob.ReadShape(page))
        when (result) {
            is PageResult.Ready -> listener.onShape(page, result.image)
            is PageResult.Failed, is PageResult.Unsupported -> {
                @Suppress("UNCHECKED_CAST")
                failures[page] = result as PageResult<Nothing>
                failedShapes += page
                listener.onShape(page, FAILED_PAGE_SHAPE)
                listener.onChanged()
            }
        }
    }

    private fun launchPrepare(target: ViewTarget): Job = scope.launch {
        val pages = coroutineScope {
            target.view.pages.mapIndexed { i, page -> async { preparePage(page, target.sizes[i], target.crop) } }.awaitAll()
        }
        running.remove(target)
        cache.put(target.view, PreparedView(target, pages))?.let { old ->
            old.pages.forEach { if (it is PreparedPage.Image) release(it.image) }
        }
        listener.onChanged()
    }

    private suspend fun preparePage(page: Int, size: PixelSize, crop: Side?): PreparedPage<T> {
        failures[page]?.let { return PreparedPage.Error(it) }
        return when (val result = loader.prepare(page, size, crop)) {
            is PageResult.Ready -> PreparedPage.Image(result.image)
            is PageResult.Failed -> PreparedPage.Error(result).also { failures[page] = result }
            is PageResult.Unsupported -> PreparedPage.Error(result).also { failures[page] = result }
        }
    }
}
