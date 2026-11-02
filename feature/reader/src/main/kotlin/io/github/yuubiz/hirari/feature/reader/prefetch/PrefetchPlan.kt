package io.github.yuubiz.hirari.feature.reader.prefetch

import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.feature.reader.Turn

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
) {
    init {
        require(current in views.indices) { "current $current is outside the views" }
    }
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
 * The prefetch plan of docs/spec.md section 5.3. The order is the current view, the next two
 * pages with the next view, the previous view, and then two pages ahead for every page behind.
 * Reaches count from the edge of the current view. Each layer that does
 * not fit the budget loses its farthest items first, and raw bytes take at most half the budget.
 */
fun planPrefetch(request: PrefetchRequest): PrefetchPlan {
    val candidates = Candidates(request)
    val shown = request.views[request.current]
    candidates.addCurrent(shown)
    val focus = candidates.size
    val ahead = Walk(request, request.travel)
    val behind = Walk(request, if (request.travel == Turn.Forward) Turn.Back else Turn.Forward)
    val next = ahead.take(2)
    candidates.addPages(next)
    candidates.addViews(next) { it == 1 }
    candidates.add(behind.takeThroughView(1))
    candidates.addViews(next) { it > 1 }
    while (ahead.hasNext || behind.hasNext) {
        candidates.add(ahead.take(2))
        candidates.add(behind.take(1))
    }
    return candidates.select(shown, focus)
}

private class Candidate(val job: PrefetchJob, val bytes: Long, val current: Boolean = false)

/** One page in walking order, or `null` for a view whose page the walk has already passed. */
private class Step(val page: Int?, val distance: Int, val completes: MutableList<Pair<PageView, Int>> = mutableListOf())

private class Candidates(private val request: PrefetchRequest) {
    private val list = mutableListOf<Candidate>()
    val size: Int get() = list.size

    fun addCurrent(view: PageView) {
        for (page in view.pages) {
            if (request.shapes[page] is PageShape.Unknown) list += Candidate(PrefetchJob.ReadShape(page), 0)
            list += Candidate(PrefetchJob.ReadRaw(page), request.rawBytes(page))
        }
        if (view.pages.all { request.shapes[it] is PageShape.Known }) {
            list += Candidate(PrefetchJob.PrepareView(view), request.viewBytes(view), current = true)
        }
    }

    fun add(steps: List<Pair<Step, Turn>>) {
        for (step in steps) {
            addPages(listOf(step))
            addViews(listOf(step)) { true }
        }
    }

    fun addPages(steps: List<Pair<Step, Turn>>) {
        val depths = request.depths
        for ((step, way) in steps) {
            val page = step.page ?: continue
            if (step.distance <= depths.shapePages.toward(request.travel, way) && request.shapes[page] is PageShape.Unknown) {
                list += Candidate(PrefetchJob.ReadShape(page), 0)
            }
            if (step.distance <= depths.rawPages.toward(request.travel, way)) {
                list += Candidate(PrefetchJob.ReadRaw(page), request.rawBytes(page))
            }
        }
    }

    /** Adds the prepared views that [steps] complete, of those whose view distance passes [distances]. */
    fun addViews(steps: List<Pair<Step, Turn>>, distances: (Int) -> Boolean) {
        for ((step, way) in steps) {
            for ((view, distance) in step.completes) {
                val known = view.pages.all { request.shapes[it] is PageShape.Known }
                if (known && distances(distance) && distance <= request.depths.preparedViews.toward(request.travel, way)) {
                    list += Candidate(PrefetchJob.PrepareView(view), request.viewBytes(view))
                }
            }
        }
    }

    /** Candidates before [focus] belong to the current view and stay even while prefetch is paused. */
    fun select(shown: PageView, focus: Int): PrefetchPlan {
        val budget = request.budgetBytes
        var total = list.firstOrNull { it.current }?.bytes ?: 0L
        var raw = 0L
        var viewsOpen = true
        var rawOpen = true
        val jobs = mutableListOf<PrefetchJob>()
        val keptViews = mutableSetOf(shown)
        val keptRaw = mutableSetOf<Int>()
        list.forEachIndexed { index, candidate ->
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
            val wanted = admitted && (index < focus || !request.paused) && !request.resident.holds(candidate.job)
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
        val passed = request.views[request.current].pages.toMutableSet()
        val result = mutableListOf<Step>()
        var pages = 0
        var index = request.current + delta
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
