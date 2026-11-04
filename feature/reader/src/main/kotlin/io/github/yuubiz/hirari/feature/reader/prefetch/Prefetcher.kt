package io.github.yuubiz.hirari.feature.reader.prefetch

import io.github.yuubiz.hirari.core.image.MarginScan
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.feature.reader.PagePart
import io.github.yuubiz.hirari.core.model.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

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
 * What is done to a page after it is scaled, in the order of docs/spec.md section 4.8: the [rotation], the [part]
 * of the turned page it shows (what 余白トリミング keeps, and for a half its half of that), then シャープ when
 * [sharpen]. The colour steps of 画像補正 are drawn, not prepared.
 */
data class PageSteps(val part: PagePart = PagePart.Whole, val rotation: Rotation = Rotation.None, val sharpen: Boolean = false)

/**
 * The pixel size of each page of [view] in reading order, as shown, and the [steps] that make each. A view
 * prepared for other sizes or steps, before the screen turned or the scale mode, the rotation, or the trim
 * changed, is stale.
 */
data class ViewTarget(val view: PageView, val sizes: List<PixelSize>, val steps: List<PageSteps> = sizes.map { PageSteps() }) {
    val bytes: Long get() = sizes.sumOf { it.width.toLong() * it.height * 4 }
}

class PreparedView<out T>(val target: ViewTarget, val pages: List<PreparedPage<T>>)

/**
 * A view to prepare: its [target], and for each page whether its margins are read on the way. Turning the trim on
 * or off changes [scans] and not the target, so a view being prepared then starts again with the scans it now needs.
 */
private data class Preparation(val target: ViewTarget, val scans: List<Boolean>)

/** The pixels [PageLoader.prepare] made, and the margins it read from the whole turned page when asked to. */
class MadePage<out T>(val image: T, val margins: MarginScan? = null)

/**
 * Reads the pages of an open book. Each call returns a result and throws nothing but cancellation. [R] is the
 * encoded data of a page the raw-data layer holds (docs/spec.md section 5.3); the calls given it decode from it
 * without reading the book again.
 */
interface PageLoader<R, T> {
    /** Updates the order of queued raw reads, including reads for shapes and the disk stash. */
    fun prioritize(pages: List<Int>) = Unit

    suspend fun invalidate(page: Int) = Unit
    fun fromStash(raw: R): Boolean = false

    suspend fun raw(page: Int): PageResult<R>

    /** What holding [raw] takes from the memory budget. */
    fun bytesOf(raw: R): Long

    /** What holding the data of [page] will take, before it is read. */
    fun rawEstimate(page: Int): Long

    suspend fun shape(page: Int, raw: R?): PageResult<PageShape.Known>

    /**
     * The pixels of [page] at [size] as shown, made by [steps]; [size] is that of the part shown. With
     * [readMargins], the margins of the whole turned page come back with them (docs/spec.md section 4.8).
     */
    suspend fun prepare(page: Int, size: PixelSize, steps: PageSteps, raw: R?, readMargins: Boolean = false): PageResult<MadePage<T>>
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
    /** Pages whose margins 余白トリミング wants and does not hold yet. A view of one is prepared again to read them. */
    val readsMargins: (Int) -> Boolean = { false },
    /** The views on screen; more than [current] only in 縦に連続. */
    val visible: IntRange = current..current,
)

/**
 * The per-book prefetch executor of docs/spec.md sections 5.3 and 8.3. Each [update] makes a new plan, cancels
 * the running jobs the plan no longer wants, starts the ones it adds, and evicts what it no longer keeps. Every
 * call and every job's bookkeeping runs in [scope], which is confined to one thread; the loader moves its work
 * off it. The raw data a job holds goes to the shapes and views read after it, and a view whose page is still
 * being read waits for that read instead of reading the page again.
 */
class Prefetcher<R, T>(
    private val scope: CoroutineScope,
    private val loader: PageLoader<R, T>,
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

        /** The margins of [page] were read while it was prepared. */
        fun onMargins(page: Int, scan: MarginScan)
    }

    private val cache = HashMap<PageView, PreparedView<T>>()
    private val running = HashMap<Any, Job>()
    private val failures = HashMap<Int, PageResult<Nothing>>()
    private val failedShapes = HashSet<Int>()

    /**
     * Pages whose failed shape is read again. The reader lays them out by [FAILED_PAGE_SHAPE] until then, so the
     * plan counts their shapes as unknown: it reads them in reach and prepares no view of them by the stand-in.
     */
    private val reshaping = HashSet<Int>()

    /** Pages whose raw data failed to read. They are not read again until a [retry]. */
    private val failedRaws = HashSet<Int>()
    private val raws = HashMap<Int, R>()
    private val recovering = HashMap<Int, Deferred<PageResult<R>>>()

    /** What the data of each page took once read. It still counts after the data is dropped, so a page that does not fit is not read again. */
    private val rawSizes = HashMap<Int, Long>()

    /** The steps, with the whole page as the part, a page failed to prepare with. Other steps read it again. */
    private val failedSteps = HashMap<Int, PageSteps>()
    private var last: PrefetchInput? = null
    private var firstViewDrawn = false
    private var visible = true

    fun onStop() {
        visible = false
        last?.let(::update)
    }

    fun onStart() {
        visible = true
        last?.let(::update)
    }

    val canPrefetch: Boolean get() = firstViewDrawn

    /** Called after the surface drew a prepared view, including its error cards. */
    fun drawn(view: PageView) {
        if (firstViewDrawn || cache[view] == null) return
        val input = last ?: return
        if (view != input.views[input.current] || input.visible.any { cache[input.views[it]] == null }) return
        firstViewDrawn = true
        update(input)
    }

    private fun failedFirstView() {
        val input = last ?: return
        if (!firstViewDrawn && input.visible.all { index -> input.views[index].pages.all { it in failures } }) {
            firstViewDrawn = true
            update(input)
        }
    }

    /** The view on screen when memory ran low. Prefetch stays paused until the reader leaves it. */
    private var trimmedOn: PageView? = null

    val failedPages: Map<Int, PageResult<Nothing>> get() = failures

    /** The prepared [view], even when it is stale, so a view keeps drawing until its replacement is ready. */
    operator fun get(view: PageView): PreparedView<T>? = cache[view]

    /** The raw data of [page], if the raw-data layer holds it. */
    fun raw(page: Int): R? = raws[page]

    fun update(input: PrefetchInput) {
        last = input
        if (trimmedOn != input.views[input.current]) trimmedOn = null
        val resident = Resident(
            views = cache.filter { (view, prepared) ->
                prepared.target == input.targets(view) && view.pages.none { it !in failures && input.readsMargins(it) }
            }.keys,
        )
        val plan = planPrefetch(
            PrefetchRequest(
                views = input.views,
                current = input.current,
                shapes = input.shapes.mapIndexed { page, shape -> if (page in reshaping) PageShape.Unknown else shape },
                travel = input.travel,
                budgetBytes = budgetBytes,
                viewBytes = { input.targets(it)?.bytes ?: 0L },
                rawBytes = { page -> rawSizes[page] ?: loader.rawEstimate(page) },
                resident = resident.copy(rawPages = raws.keys.toSet()),
                depths = depths,
                paused = !visible || input.paused || trimmedOn != null,
                visible = input.visible,
            ),
        )
        val dropped = cache.keys.filter { it !in plan.keep.views && it !in input.shown }
        dropped.forEach(::evict)
        val shownPages = input.shown.flatMapTo(HashSet()) { it.pages }
        raws.keys.retainAll { it in plan.keep.rawPages || it in shownPages }
        recovering.keys.retainAll { it in raws || recovering[it]?.isActive == true }
        val forward = input.travel == Turn.Forward
        val ahead = if (forward) (input.visible.last + 1..input.views.lastIndex).toList()
            else (input.visible.first - 1 downTo 0).toList()
        val behind = if (forward) (input.visible.first - 1 downTo 0).toList()
            else (input.visible.last + 1..input.views.lastIndex).toList()
        val order = (input.visible.toList() + ahead + behind).flatMap { index ->
            val pages = input.views[index].pages
            if (forward || index in input.visible) pages else pages.asReversed()
        }.distinct()
        loader.prioritize(order)
        val early = (input.visible.toList() + ahead.take(1)).flatMapTo(HashSet()) { input.views[it].pages }
        val wanted = LinkedHashMap<Any, () -> Job>()
        for (job in plan.jobs) {
            val pages = when (job) {
                is PrefetchJob.ReadShape -> listOf(job.page)
                is PrefetchJob.ReadRaw -> listOf(job.page)
                is PrefetchJob.PrepareView -> job.view.pages
            }
            if (!firstViewDrawn && pages.any { it !in early }) continue
            when (job) {
                is PrefetchJob.ReadShape -> wanted[job] = { launchShape(job.page) }
                is PrefetchJob.PrepareView -> input.targets(job.view)?.let { target ->
                    val preparation = Preparation(target, job.view.pages.map(input.readsMargins))
                    wanted[preparation] = { launchPrepare(preparation) }
                }
                is PrefetchJob.ReadRaw -> if (job.page !in failedRaws) wanted[job] = { launchRaw(job.page) }
            }
        }
        val wantedPages = wanted.keys.flatMapTo(HashSet()) { key ->
            when (key) {
                is PrefetchJob.ReadRaw -> listOf(key.page)
                is PrefetchJob.ReadShape -> listOf(key.page)
                is Preparation -> key.target.view.pages
                else -> emptyList()
            }
        }
        recovering.entries.filter { it.key !in wantedPages && it.value.isActive }.forEach { (page, job) ->
            job.cancel()
            recovering.remove(page)
        }
        for ((key, job) in running.entries.toList()) {
            if (key !in wanted) {
                job.cancel()
                running.remove(key)
            }
        }
        // Register raw jobs before shapes and views can ask rawFor(), including on Main.immediate.
        for ((key, start) in wanted.entries.sortedBy { if (it.key is PrefetchJob.ReadRaw) 0 else 1 }) {
            if (running[key]?.isActive != true) running[key] = start()
        }
        if (dropped.isNotEmpty()) listener.onChanged()
    }

    /**
     * `TRIM_MEMORY_RUNNING_LOW` and above: everything but the views on screen goes, and so do the jobs that would
     * bring it back, until the reader moves to another view.
     */
    fun trim() {
        val input = last ?: return
        trimmedOn = input.views[input.current]
        val dropped = cache.keys.filter { it !in input.shown }
        dropped.forEach(::evict)
        val shownPages = input.shown.flatMapTo(HashSet()) { it.pages }
        raws.keys.retainAll { it in shownPages }
        recovering.entries.filter { it.key !in shownPages }.forEach { (page, job) ->
            job.cancel()
            recovering.remove(page)
        }
        if (dropped.isNotEmpty()) listener.onChanged()
        update(input)
    }

    /** Reads [page] again: its shape if that failed, then the views that hold it. */
    fun retry(page: Int) {
        if (page !in failures) return
        scope.launch {
            running.remove(PrefetchJob.ReadRaw(page))?.cancel()
            recovering.remove(page)?.cancel()
            raws.remove(page)
            loader.invalidate(page)
            forget(page)
            listener.onChanged()
            last?.let(::update)
        }
    }

    /**
     * After the connection came back: every page it lost is read again, once for this reconnect (docs/spec.md
     * section 6.5). The next plan reads the ones in reach; the others are read when the reader gets to them.
     */
    fun retryLost() {
        val lost = failures.filterValues { it is PageResult.Failed && it.reason == FailureReason.ConnectionLost && it.retryable }.keys
        if (lost.isEmpty()) return
        lost.toList().forEach(::forget)
        listener.onChanged()
        last?.let(::update)
    }

    /** Forgets that [page] failed, with the views that show it; false when it had not. */
    private fun forget(page: Int): Boolean {
        failures.remove(page) ?: return false
        failedSteps.remove(page)
        failedRaws.remove(page)
        cache.keys.filter { page in it.pages }.forEach(::evict)
        if (failedShapes.remove(page)) reshaping += page
        return true
    }

    /** Cancels every job and releases every view. */
    fun close() {
        running.values.forEach { it.cancel() }
        running.clear()
        cache.keys.toList().forEach(::evict)
        raws.clear()
        recovering.values.forEach { it.cancel() }
        recovering.clear()
    }

    private fun evict(view: PageView) {
        cache.remove(view)?.pages?.forEach { if (it is PreparedPage.Image) release(it.image) }
    }

    private fun launchRaw(page: Int): Deferred<R?> = scope.async {
        val result = recovering[page]?.await() ?: loader.raw(page)
        running.remove(PrefetchJob.ReadRaw(page))
        when (result) {
            is PageResult.Ready -> result.image.also { raw ->
                val counted = rawSizes[page] ?: loader.rawEstimate(page)
                val size = loader.bytesOf(raw)
                raws[page] = raw
                rawSizes[page] = size
                if (size > counted) last?.let(::update)
            }
            is PageResult.Failed, is PageResult.Unsupported -> {
                @Suppress("UNCHECKED_CAST")
                failures[page] = result as PageResult<Nothing>
                failedRaws += page
                failedFirstView()
                listener.onChanged()
                null
            }
        }
    }

    /** The raw data of [page]: held, or from the read in flight. `null` when there is neither or the read stopped. */
    private suspend fun rawFor(page: Int): R? {
        raws[page]?.let { return it }
        @Suppress("UNCHECKED_CAST")
        val reading = running[PrefetchJob.ReadRaw(page)] as? Deferred<R?> ?: return null
        return try {
            reading.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private fun launchShape(page: Int): Job = scope.launch {
        val raw = rawFor(page)
        var result = loader.shape(page, raw)
        if (result !is PageResult.Ready && raw != null && loader.fromStash(raw)) {
            result = when (val fresh = recover(page)) {
                is PageResult.Ready -> loader.shape(page, fresh.image)
                is PageResult.Failed -> fresh
                is PageResult.Unsupported -> fresh
            }
        }
        running.remove(PrefetchJob.ReadShape(page))
        val reshaped = reshaping.remove(page)
        when (result) {
            is PageResult.Ready -> listener.onShape(page, result.image)
            is PageResult.Failed, is PageResult.Unsupported -> {
                @Suppress("UNCHECKED_CAST")
                failures[page] = result as PageResult<Nothing>
                failedShapes += page
                failedFirstView()
                listener.onShape(page, FAILED_PAGE_SHAPE)
                listener.onChanged()
            }
        }
        if (reshaped) last?.let(::update)
    }

    /**
     * Prepares [preparation] and puts it in the cache, then reports the margins read on the way. A report changes
     * the target of the view, so it comes after this job is done with it.
     */
    private fun launchPrepare(preparation: Preparation): Job = scope.launch {
        val target = preparation.target
        val pages = arrayOfNulls<PreparedPage<T>>(target.view.pages.size)
        val margins = arrayOfNulls<MarginScan>(target.view.pages.size)
        try {
            coroutineScope {
                target.view.pages.forEachIndexed { i, page ->
                    launch { pages[i] = preparePage(page, target.sizes[i], target.steps[i], preparation.scans[i]) { margins[i] = it } }
                }
            }
        } catch (e: CancellationException) {
            pages.forEach { if (it is PreparedPage.Image) release(it.image) }
            throw e
        }
        running.remove(preparation)
        cache.put(target.view, PreparedView(target, pages.map { it!! }))?.let { old ->
            old.pages.forEach { if (it is PreparedPage.Image) release(it.image) }
        }
        failedFirstView()
        listener.onChanged()
        margins.forEachIndexed { i, scan -> if (scan != null) listener.onMargins(target.view.pages[i], scan) }
    }

    private suspend fun preparePage(page: Int, size: PixelSize, steps: PageSteps, readMargins: Boolean, read: (MarginScan) -> Unit): PreparedPage<T> {
        val made = steps.copy(part = PagePart.Whole)
        val raw = rawFor(page)
        failures[page]?.let { if (page in failedShapes || page in failedRaws || failedSteps[page] == made) return PreparedPage.Error(it) }
        var result = loader.prepare(page, size, steps, raw, readMargins)
        if (result !is PageResult.Ready && raw != null && loader.fromStash(raw)) {
            when (val fresh = recover(page)) {
                is PageResult.Ready -> result = loader.prepare(page, size, steps, fresh.image, readMargins)
                is PageResult.Failed -> result = fresh
                is PageResult.Unsupported -> result = fresh
            }
        }
        return when (val result = result) {
            is PageResult.Ready -> PreparedPage.Image(result.image.image).also {
                result.image.margins?.let(read)
                failures.remove(page)
                failedSteps.remove(page)
            }
            is PageResult.Failed -> failed(page, made, result)
            is PageResult.Unsupported -> failed(page, made, result)
        }
    }

    /** Both halves and shape probes share one replacement of a corrupt cached entry. */
    private suspend fun recover(page: Int): PageResult<R> {
        val fresh = recovering.getOrPut(page) {
            scope.async {
                raws.remove(page)
                loader.invalidate(page)
                loader.raw(page)
            }
        }.await()
        if (fresh is PageResult.Ready) {
            val counted = rawSizes[page] ?: loader.rawEstimate(page)
            raws[page] = fresh.image
            val size = loader.bytesOf(fresh.image)
            rawSizes[page] = size
            if (size > counted) last?.let(::update)
        }
        return fresh
    }

    private fun failed(page: Int, steps: PageSteps, result: PageResult<Nothing>): PreparedPage<Nothing> {
        failures[page] = result
        failedSteps[page] = steps
        return PreparedPage.Error(result)
    }
}
