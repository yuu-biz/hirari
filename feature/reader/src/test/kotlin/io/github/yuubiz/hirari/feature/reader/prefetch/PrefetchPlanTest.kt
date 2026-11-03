package io.github.yuubiz.hirari.feature.reader.prefetch

import io.github.yuubiz.hirari.core.model.HalfPart
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.PageView.Half
import io.github.yuubiz.hirari.core.model.PageView.One
import io.github.yuubiz.hirari.core.model.PageView.Two
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.layoutViews
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.model.Turn.Back
import io.github.yuubiz.hirari.core.model.Turn.Forward
import io.github.yuubiz.hirari.feature.reader.prefetch.PrefetchJob.PrepareView
import io.github.yuubiz.hirari.feature.reader.prefetch.PrefetchJob.ReadRaw
import io.github.yuubiz.hirari.feature.reader.prefetch.PrefetchJob.ReadShape
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MB = 1024L * 1024L

class PrefetchPlanTest {
    private val tall = PageShape.Known(700, 1000)
    private val wide = PageShape.Known(1400, 1000)

    private fun single(count: Int) = List(count) { One(it) }

    private fun request(
        views: List<PageView>,
        current: Int,
        shapes: List<PageShape> = List(views.maxOf { it.pages.max() } + 1) { tall },
        travel: Turn = Forward,
        budget: Long = Long.MAX_VALUE / 4,
        viewBytes: Long = 1,
        rawBytes: Long = 1,
        resident: Resident = Resident(),
        depths: PrefetchDepths = PrefetchDepths(),
        paused: Boolean = false,
        visible: IntRange = current..current,
    ) = PrefetchRequest(views, current, shapes, travel, budget, { viewBytes }, { rawBytes }, resident, depths, paused, visible)

    private fun PrefetchPlan.prepared() = jobs.filterIsInstance<PrepareView>().map { it.view }.toSet()

    private fun PrefetchPlan.raw() = jobs.filterIsInstance<ReadRaw>().map { it.page }.toSet()

    @Test
    fun theCurrentViewComesFirstThenTheNextViewThenThePreviousView() {
        val plan = planPrefetch(request(single(100), current = 50))
        assertEquals(
            listOf(
                ReadRaw(50), PrepareView(One(50)),
                ReadRaw(51), PrepareView(One(51)),
                ReadRaw(49), PrepareView(One(49)),
                ReadRaw(52), PrepareView(One(52)),
                ReadRaw(53), ReadRaw(54), ReadRaw(48),
                ReadRaw(55), ReadRaw(56), ReadRaw(47),
            ),
            plan.jobs.take(14),
        )
    }

    @Test
    fun everyViewOnScreenComesFirstAndTheReachesCountFromTheEdgesOfTheScreen() {
        val plan = planPrefetch(request(single(100), current = 50, visible = 50..52))
        assertEquals(
            listOf(ReadRaw(50), PrepareView(One(50)), ReadRaw(51), PrepareView(One(51)), ReadRaw(52), PrepareView(One(52)), ReadRaw(53), PrepareView(One(53))),
            plan.jobs.take(8),
        )
        assertEquals((49..54).map(::One).toSet(), plan.prepared())
        assertEquals((34..84).toSet(), plan.raw())
        assertTrue(plan.keep.views.containsAll((50..52).map(::One)))
    }

    @Test
    fun layersReachTheirDefaultDepths() {
        val plan = planPrefetch(request(single(100), current = 50))
        assertEquals(setOf(One(49), One(50), One(51), One(52)), plan.prepared())
        assertEquals((34..82).toSet(), plan.raw())
    }

    @Test
    fun shapesAreReadFourPagesAheadAndTwoBehindBeforeTheirRawBytes() {
        val plan = planPrefetch(request(single(100), current = 50, shapes = List(100) { if (it == 50) tall else PageShape.Unknown }))
        val shapes = plan.jobs.filterIsInstance<ReadShape>().map { it.page }
        assertEquals(setOf(51, 52, 53, 54, 49, 48), shapes.toSet())
        for (page in shapes) assertTrue(plan.jobs.indexOf(ReadShape(page)) < plan.jobs.indexOf(ReadRaw(page)), "shape of $page first")
        assertEquals(setOf(One(50)), plan.prepared())
    }

    @Test
    fun aViewWithAnUnknownPageIsNotPrepared() {
        val shapes = listOf(tall, tall, PageShape.Unknown, tall)
        val plan = planPrefetch(request(listOf(Two(0, 1), Two(2, 3)), current = 0, shapes = shapes))
        assertEquals(setOf(Two(0, 1)), plan.prepared())
        assertTrue(ReadShape(2) in plan.jobs)
    }

    @Test
    fun goingBackwardSwapsTheDepths() {
        val plan = planPrefetch(request(single(100), current = 50, travel = Back))
        assertEquals(
            listOf(ReadRaw(50), PrepareView(One(50)), ReadRaw(49), PrepareView(One(49)), ReadRaw(51), PrepareView(One(51)), ReadRaw(48)),
            plan.jobs.take(7),
        )
        assertEquals(setOf(One(48), One(49), One(50), One(51)), plan.prepared())
        assertEquals((18..66).toSet(), plan.raw())
    }

    @Test
    fun twoBackwardTurnsInARowReverseTheTravel() {
        assertEquals(Forward, travelDirection(emptyList()))
        assertEquals(Forward, travelDirection(listOf(Back)))
        assertEquals(Forward, travelDirection(listOf(Back, Forward)))
        assertEquals(Forward, travelDirection(listOf(Back, Back, Forward)))
        assertEquals(Back, travelDirection(listOf(Forward, Back, Back)))
    }

    @Test
    fun rawBytesTakeAtMostHalfTheBudgetAndLoseTheFarthestPages() {
        val plan = planPrefetch(request(single(100), current = 50, budget = 20))
        assertEquals((47..56).toSet(), plan.keep.rawPages)
    }

    @Test
    fun preparedViewsLoseTheLowestPriorityFirst() {
        val plan = planPrefetch(request(single(100), current = 50, budget = 25, viewBytes = 10, rawBytes = 0))
        assertEquals(setOf(One(50), One(51)), plan.keep.views)
    }

    @Test
    fun rawBytesWaitBehindTheViewsThatDoNotNeedThem() {
        val resident = Resident(views = setOf(One(50)), rawPages = setOf(50))
        val plan = planPrefetch(request(single(100), current = 50, budget = 100 * MB, viewBytes = 40 * MB, rawBytes = 20 * MB, resident = resident))
        assertEquals(setOf(One(50), One(51)), plan.keep.views)
        assertEquals(setOf(51), plan.keep.rawPages)
        assertEquals(listOf(ReadRaw(51), PrepareView(One(51))), plan.jobs)
    }

    @Test
    fun theShownViewStaysEvenWhenItAloneExceedsTheBudget() {
        val shown = One(50)
        val resident = Resident(views = setOf(shown, One(51)), rawPages = setOf(50))
        val plan = planPrefetch(request(single(100), current = 50, budget = 5, viewBytes = 10, resident = resident))
        assertEquals(setOf(shown), plan.keep.views)
        assertEquals(Resident(views = setOf(One(51)), rawPages = setOf(50)), plan.evictions(resident))
    }

    @Test
    fun residentItemsAreKeptWithoutJobs() {
        val resident = Resident(views = setOf(One(51)), rawPages = setOf(51, 99))
        val plan = planPrefetch(request(single(100), current = 50, resident = resident))
        assertFalse(PrepareView(One(51)) in plan.jobs)
        assertFalse(ReadRaw(51) in plan.jobs)
        assertTrue(One(51) in plan.keep.views)
        assertEquals(Resident(rawPages = setOf(99)), plan.evictions(resident))
    }

    @Test
    fun turningForwardMakesOnlyTheViewThatFellBehindObsolete() {
        val before = planPrefetch(request(single(100), current = 10))
        val after = planPrefetch(request(single(100), current = 11))
        assertEquals(setOf<PrefetchJob>(PrepareView(One(9))), before.obsoleteIn(after))
    }

    @Test
    fun jumpingCancelsEveryJobOutsideTheNewRange() {
        val before = planPrefetch(request(single(100), current = 10))
        val after = planPrefetch(request(single(100), current = 80))
        assertEquals(before.jobs.toSet(), before.obsoleteIn(after))
    }

    @Test
    fun pausingLeavesOnlyTheCurrentViewButKeepsWhatIsLoaded() {
        val resident = Resident(views = setOf(One(51)), rawPages = setOf(52))
        val plan = planPrefetch(request(single(100), current = 50, resident = resident, paused = true))
        assertEquals(listOf(ReadRaw(50), PrepareView(One(50))), plan.jobs)
        assertEquals(Resident(), plan.evictions(resident))
    }

    @Test
    fun pausingKeepsWhatIsLoadedEvenOutsideTheRangeAndTheBudget() {
        val resident = Resident(views = setOf(One(50), One(99)), rawPages = setOf(0, 99))
        fun plan(paused: Boolean) =
            planPrefetch(request(single(100), current = 50, budget = 10, viewBytes = 10, rawBytes = 10, resident = resident, paused = paused))
        assertEquals(Resident(views = setOf(One(99)), rawPages = setOf(0, 99)), plan(paused = false).evictions(resident))
        assertEquals(Resident(), plan(paused = true).evictions(resident))
    }

    @Test
    fun bothHalvesOfAWidePageArePrepared() {
        val views = listOf(Half(0, HalfPart.First), Half(0, HalfPart.Second), One(1), One(2))
        val plan = planPrefetch(request(views, current = 0, shapes = listOf(wide, tall, tall)))
        assertEquals(
            listOf(ReadRaw(0), PrepareView(Half(0, HalfPart.First)), PrepareView(Half(0, HalfPart.Second)), ReadRaw(1), PrepareView(One(1))),
            plan.jobs.take(5),
        )
        assertEquals(1, plan.jobs.count { it == ReadRaw(0) })
    }

    @Test
    fun aLowRamDevicePreparesOnlyTheNextView() {
        val plan = planPrefetch(request(single(100), current = 50, depths = prefetchDepths(lowRamDevice = true)))
        assertEquals(setOf(One(50), One(51)), plan.prepared())
    }

    @Test
    fun trimmingMemoryDropsEverythingButTheShownView() {
        val resident = Resident(views = setOf(One(4), One(5), One(6)), rawPages = setOf(5, 6, 7))
        assertEquals(Resident(views = setOf(One(4), One(6)), rawPages = setOf(5, 6, 7)), evictionsOnTrim(resident, One(5)))
    }

    @Test
    fun theBudgetFollowsTheMemoryClassWithinItsBounds() {
        assertEquals(384 * MB, memoryBudget(memoryClassMb = 256, totalMemBytes = 8192 * MB, lowRamDevice = false))
        assertEquals(96 * MB, memoryBudget(memoryClassMb = 48, totalMemBytes = 8192 * MB, lowRamDevice = false))
        assertEquals(512 * MB, memoryBudget(memoryClassMb = 512, totalMemBytes = 8192 * MB, lowRamDevice = false))
    }

    @Test
    fun theBudgetIsAtMostATenthOfTheTotalMemory() {
        assertEquals(300 * MB, memoryBudget(memoryClassMb = 256, totalMemBytes = 3000 * MB, lowRamDevice = false))
    }

    @Test
    fun aLowRamDeviceGetsAtMostSixtyFourMegabytes() {
        assertEquals(64 * MB, memoryBudget(memoryClassMb = 256, totalMemBytes = 8192 * MB, lowRamDevice = true))
    }

    private data class Scenario(
        val views: List<PageView>,
        val shapes: List<PageShape>,
        val current: Int,
        val travel: Turn,
        val budget: Long,
        val resident: Resident,
        val paused: Boolean,
        val lowRam: Boolean,
        /** The views on screen: more than the current one only in 縦に連続. */
        val visible: IntRange = current..current,
    ) {
        fun viewBytes(view: PageView) = 3L + view.pages.sum() % 5

        fun rawBytes(page: Int) = 1L + page % 3

        val onScreen: List<PageView> get() = visible.map(views::get)

        fun plan() = planPrefetch(
            PrefetchRequest(views, current, shapes, travel, budget, ::viewBytes, ::rawBytes, resident, prefetchDepths(lowRam), paused, visible),
        )

        /** How many views [index] lies past the edge of the screen. */
        fun distance(index: Int): Int = when {
            index < visible.first -> index - visible.first
            index > visible.last -> index - visible.last
            else -> 0
        }
    }

    private val scenarios = arbitrary {
        val shapes = Arb.list(Arb.element(tall, wide, PageShape.Unknown), 1..60).bind()
        val mode = Arb.element(LayoutMode.Single, LayoutMode.Spread, LayoutMode.SplitWide, LayoutMode.Vertical).bind()
        val settings = LayoutSettings(mode, Arb.enum<ReadingDirection>().bind(), Arb.boolean().bind())
        val views = layoutViews(shapes.size, shapes::get, settings, Arb.int(shapes.indices).bind())
        val resident = Resident(
            views = Arb.list(Arb.element(views), 0..8).bind().toSet(),
            rawPages = Arb.list(Arb.int(shapes.indices), 0..20).bind().toSet(),
        )
        val first = Arb.int(views.indices).bind()
        val visible = if (mode == LayoutMode.Vertical) first..Arb.int(first..minOf(first + 3, views.lastIndex)).bind() else first..first
        Scenario(
            views, shapes, Arb.int(visible).bind(), Arb.enum<Turn>().bind(), Arb.long(0L..120L).bind(),
            resident, Arb.boolean().bind(), Arb.boolean().bind(), visible,
        )
    }

    @Test
    fun theShownViewIsNeverEvicted() = runBlocking<Unit> {
        checkAll(scenarios) { scenario ->
            for (shown in scenario.onScreen) assertFalse(shown in scenario.plan().evictions(scenario.resident).views)
            val shown = scenario.views[scenario.current]
            assertFalse(shown in evictionsOnTrim(scenario.resident, shown).views)
        }
    }

    @Test
    fun whatThePlanKeepsFitsTheBudget() = runBlocking<Unit> {
        checkAll(scenarios.map { it.copy(paused = false) }) { scenario ->
            val shown = scenario.onScreen.toSet()
            val keep = scenario.plan().keep
            val shownBytes = shown.filter { view -> view.pages.all { scenario.shapes[it] is PageShape.Known } }.sumOf(scenario::viewBytes)
            val raw = keep.rawPages.sumOf { scenario.rawBytes(it) }
            val others = (keep.views - shown).sumOf { scenario.viewBytes(it) } + raw
            assertTrue(others <= maxOf(0L, scenario.budget - shownBytes), "$others over ${scenario.budget} with $shownBytes shown")
            assertTrue(raw <= scenario.budget / 2, "raw $raw over half of ${scenario.budget}")
        }
    }

    @Test
    fun aPausedPlanEvictsNothing() = runBlocking<Unit> {
        checkAll(scenarios.map { it.copy(paused = true) }) { scenario ->
            assertEquals(Resident(), scenario.plan().evictions(scenario.resident))
        }
    }

    @Test
    fun jobsAreDistinctNewAndPreparable() = runBlocking<Unit> {
        checkAll(scenarios) { scenario ->
            val jobs = scenario.plan().jobs
            assertEquals(jobs.size, jobs.toSet().size, "duplicate jobs in $jobs")
            for (job in jobs) {
                when (job) {
                    is ReadShape -> assertTrue(scenario.shapes[job.page] is PageShape.Unknown)
                    is ReadRaw -> assertFalse(job.page in scenario.resident.rawPages)
                    is PrepareView -> {
                        assertFalse(job.view in scenario.resident.views)
                        assertTrue(job.view.pages.all { scenario.shapes[it] is PageShape.Known })
                        val distance = scenario.distance(scenario.views.indexOf(job.view))
                        assertTrue(distance in -2..2, "${job.view} is $distance views past the screen")
                    }
                }
            }
        }
    }
}
