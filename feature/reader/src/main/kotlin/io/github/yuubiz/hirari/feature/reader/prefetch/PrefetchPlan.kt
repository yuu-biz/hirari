package io.github.yuubiz.hirari.feature.reader.prefetch

import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Turn

/** How many items a layer holds in the reading direction ([ahead]) and against it ([behind]). */
data class Reach(val ahead: Int, val behind: Int) {
    init {
        require(ahead >= 0 && behind >= 0) { "reach $ahead, $behind must not be negative" }
    }

    fun toward(travel: Turn, step: Turn): Int = if (travel == step) ahead else behind
}

/** The layers of docs/spec.md section 5.3. [preparedViews] counts views, the others count pages. */
data class PrefetchDepths(
    val preparedViews: Reach = Reach(ahead = 2, behind = 1),
    val shapePages: Reach = Reach(ahead = 4, behind = 2),
    val rawPages: Reach = Reach(ahead = 32, behind = 16),
) {
    companion object {
        val LowRam = PrefetchDepths(preparedViews = Reach(ahead = 1, behind = 0))
    }
}

sealed interface PrefetchJob {
    data class ReadShape(val page: Int) : PrefetchJob
    data class ReadRaw(val page: Int) : PrefetchJob
    data class PrepareView(val view: PageView) : PrefetchJob
}

/** What memory holds: prepared views and the raw bytes of pages. */
data class Resident(val views: Set<PageView> = emptySet(), val rawPages: Set<Int> = emptySet()) {
    operator fun plus(other: Resident) = Resident(views + other.views, rawPages + other.rawPages)

    operator fun minus(other: Resident) = Resident(views - other.views, rawPages - other.rawPages)
}

/** The reading direction for prefetch: backward once the last two turns both went back. */
fun travelDirection(recentTurns: List<Turn>): Turn =
    if (recentTurns.size >= 2 && recentTurns.takeLast(2).all { it == Turn.Back }) Turn.Back else Turn.Forward

class PrefetchRequest(
    val views: List<PageView>,
    val current: Int,
    val shapes: List<PageShape>,
    val travel: Turn,
    val budgetBytes: Long,
    val viewBytes: (PageView) -> Long,
    val rawBytes: (Int) -> Long,
    val resident: Resident,
    val depths: PrefetchDepths = PrefetchDepths(),
    /** While the settings screen or the thumbnail strip is up, only the current view loads and nothing resident goes. */
    val paused: Boolean = false,
    /**
     * The views on screen, which all count as current; more than [current] only in 縦に連続, where every page on
     * screen is a view of its own.
     */
    val visible: IntRange = current..current,
) {
    init {
        require(current in views.indices) { "current $current is outside the views" }
        require(current in visible && visible.first >= 0 && visible.last < views.size) { "visible $visible does not hold $current" }
    }

    /** The views on screen, the current one first and then outward from it. */
    val onScreen: List<PageView> get() = visible.sortedBy { kotlin.math.abs(it - current) }.map(views::get)
}

/**
 * [jobs] are in priority order and leave out what is already resident. [keep] is everything the
 * plan wants in memory, so the rest of the resident items may go.
 */
data class PrefetchPlan(val jobs: List<PrefetchJob>, val keep: Resident) {
    fun evictions(resident: Resident): Resident = resident - keep

    /** The jobs of this plan that [next] no longer wants. Cancel them when [next] replaces this plan. */
    fun obsoleteIn(next: PrefetchPlan): Set<PrefetchJob> = jobs.toSet() - next.jobs.toSet()
}

/** What to drop on `TRIM_MEMORY_RUNNING_LOW` and above: everything but the shown view. */
fun evictionsOnTrim(resident: Resident, shown: PageView): Resident = resident - Resident(views = setOf(shown))

/**
 * The prefetch plan of docs/spec.md section 5.3. The order is the current view, the next view
 * with the shapes of the next two pages, the previous view, and then two pages ahead for every
 * page behind. A view that still has to be prepared brings the raw bytes of its pages just before
 * it. The other raw bytes of these views wait until after them. Reaches count from the edge of the
 * current view. Each layer that does not fit the budget loses its farthest items first, and raw
 * bytes take at most half the budget. In 縦に連続 every view on screen counts as the current one, and the reaches
 * count from the edges of the screen.
 */
fun planPrefetch(request: PrefetchRequest): PrefetchPlan {
    val candidates = Candidates(request)
    val onScreen = request.onScreen
    onScreen.forEach(candidates::addCurrent)
    val focus = candidates.size
    val ahead = Walk(request, request.travel)
    val behind = Walk(request, if (request.travel == Turn.Forward) Turn.Back else Turn.Forward)
    val next = ahead.take(2)
    val previous = behind.takeThroughView(1)
    candidates.addShapes(next)
    candidates.addViews(next) { it == 1 }
    candidates.addShapes(previous)
    candidates.addViews(previous) { true }
    candidates.addViews(next) { it > 1 }
    onScreen.forEach { it.pages.forEach(candidates::addRaw) }
    candidates.addRaws(next)
    candidates.addRaws(previous)
    while (ahead.hasNext || behind.hasNext) {
        candidates.add(ahead.take(2))
        candidates.add(behind.take(1))
    }
    return candidates.select(onScreen, focus)
}

private class Candidate(val job: PrefetchJob, val bytes: Long, val current: Boolean = false)

/** One page in walking order, or `null` for a view whose page the walk has already passed. */
private class Step(val page: Int?, val distance: Int, val completes: MutableList<Pair<PageView, Int>> = mutableListOf())

private class Candidates(private val request: PrefetchRequest) {
    private val list = mutableListOf<Candidate>()
    private val rawPages = mutableSetOf<Int>()
    val size: Int get() = list.size

    fun addCurrent(view: PageView) {
        for (page in view.pages) {
            if (request.shapes[page] is PageShape.Unknown) list += Candidate(PrefetchJob.ReadShape(page), 0)
        }
        if (view !in request.resident.views) view.pages.forEach(::addRaw)
        if (view.pages.all { request.shapes[it] is PageShape.Known }) {
            list += Candidate(PrefetchJob.PrepareView(view), request.viewBytes(view), current = true)
        }
    }

    fun add(steps: List<Pair<Step, Turn>>) {
        for (step in steps) {
            addShapes(listOf(step))
            addRaws(listOf(step))
            addViews(listOf(step)) { true }
        }
    }

    fun addShapes(steps: List<Pair<Step, Turn>>) {
        for ((step, way) in steps) {
            val page = step.page ?: continue
            if (step.distance <= request.depths.shapePages.toward(request.travel, way) && request.shapes[page] is PageShape.Unknown) {
                list += Candidate(PrefetchJob.ReadShape(page), 0)
            }
        }
    }

    fun addRaws(steps: List<Pair<Step, Turn>>) {
        for ((step, way) in steps) {
            val page = step.page ?: continue
            if (step.distance <= request.depths.rawPages.toward(request.travel, way)) addRaw(page)
        }
    }

    fun addRaw(page: Int) {
        if (rawPages.add(page)) list += Candidate(PrefetchJob.ReadRaw(page), request.rawBytes(page))
    }

    /**
     * Adds the prepared views that [steps] complete, of those whose view distance passes
     * [distances], each after the raw bytes it still needs.
     */
    fun addViews(steps: List<Pair<Step, Turn>>, distances: (Int) -> Boolean) {
        for ((step, way) in steps) {
            for ((view, distance) in step.completes) {
                val known = view.pages.all { request.shapes[it] is PageShape.Known }
                if (known && distances(distance) && distance <= request.depths.preparedViews.toward(request.travel, way)) {
                    if (view !in request.resident.views) view.pages.forEach(::addRaw)
                    list += Candidate(PrefetchJob.PrepareView(view), request.viewBytes(view))
                }
            }
        }
    }

    /** Candidates before [focus] belong to the views on screen and stay even while prefetch is paused. */
    fun select(shown: List<PageView>, focus: Int): PrefetchPlan {
        val budget = request.budgetBytes
        var total = list.filter { it.current }.sumOf { it.bytes }
        var raw = 0L
        var viewsOpen = true
        var rawOpen = true
        val jobs = mutableListOf<PrefetchJob>()
        val keptViews = shown.toMutableSet()
        val keptRaw = mutableSetOf<Int>()
        // Admission must not depend on which views still need raw data to prepare. Keep the
        // execution order below, but budget raw candidates nearest first in their existing slots.
        val ahead = Walk(request, request.travel)
        val behind = Walk(request, if (request.travel == Turn.Forward) Turn.Back else Turn.Forward)
        val rawOrder = buildList {
            shown.forEach { addAll(it.pages) }
            while (ahead.hasNext || behind.hasNext) {
                ahead.take(1).forEach { (step, _) -> step.page?.let { add(it) } }
                behind.take(1).forEach { (step, _) -> step.page?.let { add(it) } }
            }
        }.withIndex().associate { it.value to it.index }
        val nearestRaws = list.filter { it.job is PrefetchJob.ReadRaw }
            .sortedBy { rawOrder.getValue((it.job as PrefetchJob.ReadRaw).page) }.iterator()
        val admittedCandidates = HashSet<Candidate>()
        for (slot in list) {
            val candidate = if (slot.job is PrefetchJob.ReadRaw) nearestRaws.next() else slot
            val admitted = when (val job = candidate.job) {
                is PrefetchJob.ReadShape -> true
                is PrefetchJob.PrepareView -> {
                    if (!candidate.current) {
                        viewsOpen = viewsOpen && total + candidate.bytes <= budget
                        if (viewsOpen) total += candidate.bytes
                    }
                    val fits = candidate.current || viewsOpen
                    if (fits) keptViews += job.view
                    fits
                }
                is PrefetchJob.ReadRaw -> {
                    rawOpen = rawOpen && raw + candidate.bytes <= budget / 2 && total + candidate.bytes <= budget
                    if (rawOpen) {
                        raw += candidate.bytes
                        total += candidate.bytes
                        keptRaw += job.page
                    }
                    rawOpen
                }
            }
            if (admitted) admittedCandidates += candidate
        }
        list.forEachIndexed { index, candidate ->
            val wanted = candidate in admittedCandidates && (index < focus || !request.paused) && !request.resident.holds(candidate.job)
            if (wanted) jobs += candidate.job
        }
        val keep = Resident(keptViews, keptRaw)
        return PrefetchPlan(jobs, if (request.paused) keep + request.resident else keep)
    }
}

private fun Resident.holds(job: PrefetchJob): Boolean = when (job) {
    is PrefetchJob.ReadShape -> false
    is PrefetchJob.ReadRaw -> job.page in rawPages
    is PrefetchJob.PrepareView -> job.view in views
}

/** Walks the views away from the current one in the direction [way], one new page at a time. */
private class Walk(private val request: PrefetchRequest, private val way: Turn) {
    private val steps: List<Step> = buildSteps()
    private var next = 0

    val hasNext: Boolean get() = next < steps.size

    fun take(count: Int): List<Pair<Step, Turn>> {
        val end = minOf(next + count, steps.size)
        return steps.subList(next, end).map { it to way }.also { next = end }
    }

    fun takeThroughView(distance: Int): List<Pair<Step, Turn>> {
        val end = steps.indexOfFirst { step -> step.completes.any { it.second == distance } }
        return if (end < next) emptyList() else take(end + 1 - next)
    }

    private fun buildSteps(): List<Step> {
        val depths = request.depths
        val pageLimit = maxOf(depths.shapePages.toward(request.travel, way), depths.rawPages.toward(request.travel, way))
        val viewLimit = depths.preparedViews.toward(request.travel, way)
        val delta = if (way == Turn.Forward) 1 else -1
        val passed = request.visible.flatMapTo(mutableSetOf()) { request.views[it].pages }
        val result = mutableListOf<Step>()
        var pages = 0
        var index = if (way == Turn.Forward) request.visible.last + 1 else request.visible.first - 1
        var distance = 1
        while (index in request.views.indices && (pages < pageLimit || distance <= viewLimit)) {
            val view = request.views[index]
            val order = if (way == Turn.Forward) view.pages else view.pages.asReversed()
            for (page in order) {
                if (passed.add(page)) {
                    pages += 1
                    result += Step(page, pages)
                }
            }
            if (result.isEmpty()) result += Step(null, 0)
            result.last().completes += view to distance
            index += delta
            distance += 1
        }
        return result
    }
}
