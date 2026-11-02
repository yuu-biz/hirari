package io.github.yuubiz.hirari.feature.reader.prefetch

import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import io.github.yuubiz.hirari.feature.reader.LANDSCAPE
import io.github.yuubiz.hirari.feature.reader.PORTRAIT
import io.github.yuubiz.hirari.feature.reader.ReaderAction
import io.github.yuubiz.hirari.feature.reader.ReaderConfig
import io.github.yuubiz.hirari.feature.reader.ReaderEvent
import io.github.yuubiz.hirari.feature.reader.ReaderState
import io.github.yuubiz.hirari.feature.reader.TALL
import io.github.yuubiz.hirari.feature.reader.Turn
import io.github.yuubiz.hirari.feature.reader.Viewport
import io.github.yuubiz.hirari.feature.reader.spreadConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SINGLE = ReaderConfig(layout = LayoutSettings(LayoutMode.Single, ReadingDirection.RightToLeft, coverAlone = false))

private class FakeLoader(
    val shapes: Map<Int, PageResult<PageShape.Known>> = emptyMap(),
    val failing: MutableMap<Int, PageResult<Nothing>> = mutableMapOf(),
) : PageLoader<String> {
    val cancelled = mutableListOf<Int>()
    var gate: CompletableDeferred<Unit>? = null

    /** Pages that wait here besides [gate]. */
    val pageGates = mutableMapOf<Int, CompletableDeferred<Unit>>()

    override suspend fun shape(page: Int): PageResult<PageShape.Known> = shapes[page] ?: PageResult.Ready(TALL)

    override suspend fun prepare(page: Int, size: PixelSize, crop: Side?): PageResult<String> {
        try {
            gate?.await()
            pageGates[page]?.await()
            delay(1)
        } catch (e: CancellationException) {
            cancelled += page
            throw e
        }
        return failing[page] ?: PageResult.Ready("$page@${size.width}x${size.height}")
    }
}

/** The reader and its prefetcher as the screen wires them: every new state makes a new plan. */
private class Reader(scope: CoroutineScope, var state: ReaderState, val loader: FakeLoader = FakeLoader()) : Prefetcher.Listener {
    val released = mutableListOf<String>()

    /** Views the screen draws besides the ones [state] names. */
    var alsoShown: Set<PageView> = emptySet()
    val prefetcher = Prefetcher(scope, loader, { released += it }, budgetBytes = 1L shl 30, PrefetchDepths(), this)

    init {
        prefetcher.update(state.prefetchInput())
    }

    override fun onShape(page: Int, shape: PageShape.Known) = apply(ReaderEvent.ShapeLearned(page, shape))

    override fun onChanged() = Unit

    fun apply(event: ReaderEvent) {
        state = state.reduce(event)
        val input = state.prefetchInput()
        prefetcher.update(PrefetchInput(input.views, input.current, input.shapes, input.travel, input.targets, input.shown + alsoShown))
    }

    fun turn(turn: Turn) {
        apply(ReaderEvent.Act(ReaderAction.TurnView(turn), 0))
        apply(ReaderEvent.Tick(Long.MAX_VALUE))
    }

    fun preparedPages(): Set<Int> = state.pagination.views.filter { prefetcher[it] != null }.flatMap { it.pages }.toSet()
}

private fun TestScope.reader(pages: Int, config: ReaderConfig = SINGLE, viewport: Viewport = PORTRAIT, start: Int = 0, loader: FakeLoader = FakeLoader()) =
    Reader(CoroutineScope(StandardTestDispatcher(testScheduler)), ReaderState.open(pages, start, config, viewport), loader)

class PrefetcherTest {
    @Test
    fun openingReadsTheShapesAndPreparesTheCurrentViewTwoAheadAndOneBehind() = runTest {
        val reader = reader(pages = 8, start = 3)
        advanceUntilIdle()
        assertEquals(setOf(2, 3, 4, 5), reader.preparedPages())
        assertEquals("3@1080x1620", (reader.prefetcher[PageView.One(3)]!!.pages.single() as PreparedPage.Image).image)
    }

    @Test
    fun turningEvictsTheViewsOutOfReachAndReleasesThem() = runTest {
        val reader = reader(pages = 8, start = 3)
        advanceUntilIdle()
        repeat(3) { reader.turn(Turn.Forward) }
        advanceUntilIdle()
        assertEquals(setOf(5, 6, 7), reader.preparedPages())
        assertEquals(setOf("2@1080x1620", "3@1080x1620", "4@1080x1620"), reader.released.toSet())
    }

    @Test
    fun aViewOnScreenStaysWhateverThePlan() = runTest {
        val reader = reader(pages = 8)
        advanceUntilIdle()
        reader.alsoShown = setOf(PageView.One(0))
        reader.apply(ReaderEvent.GoTo(7))
        advanceUntilIdle()
        assertNotNull(reader.prefetcher[PageView.One(0)])
        assertNull(reader.prefetcher[PageView.One(1)])
    }

    @Test
    fun aJumpCancelsTheJobsItNoLongerWants() = runTest {
        val loader = FakeLoader().apply { gate = CompletableDeferred() }
        val reader = reader(pages = 8, loader = loader)
        runCurrent()
        reader.apply(ReaderEvent.GoTo(7))
        runCurrent()
        assertEquals(setOf(0, 1, 2), loader.cancelled.toSet())
        loader.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(6, 7), reader.preparedPages())
    }

    @Test
    fun aPageWhoseHeaderFailsPairsAsPortraitAndItsPartnerStillDraws() = runTest {
        val notAnImage = PageResult.Unsupported(UnsupportedKind.NotAnImage)
        val reader = reader(pages = 4, config = spreadConfig(), viewport = LANDSCAPE, loader = FakeLoader(shapes = mapOf(1 to notAnImage)))
        advanceUntilIdle()
        assertEquals(FAILED_PAGE_SHAPE, reader.state.pagination.shapes[1])
        assertEquals(PageView.Two(0, 1), reader.state.current)
        val pages = reader.prefetcher[PageView.Two(0, 1)]!!.pages
        assertIs<PreparedPage.Image<String>>(pages[0])
        assertEquals(PreparedPage.Error(notAnImage), pages[1])
        assertEquals(notAnImage, reader.prefetcher.failedPages[1])
    }

    @Test
    fun aViewPreparedForTheOldScreenIsReplacedAndReleased() = runTest {
        val reader = reader(pages = 3)
        advanceUntilIdle()
        reader.apply(ReaderEvent.ViewportChanged(Viewport(720f, 1280f, 2f)))
        advanceUntilIdle()
        assertEquals("0@720x1080", (reader.prefetcher[PageView.One(0)]!!.pages.single() as PreparedPage.Image).image)
        assertTrue("0@1080x1620" in reader.released)
    }

    @Test
    fun trimmingKeepsOnlyTheViewsOnScreen() = runTest {
        val reader = reader(pages = 8, start = 3)
        advanceUntilIdle()
        reader.prefetcher.trim()
        assertEquals(setOf(3), reader.preparedPages())
        assertEquals(3, reader.released.size)
    }

    @Test
    fun aRetryReadsTheFailedPageAgain() = runTest {
        val loader = FakeLoader(failing = mutableMapOf(0 to PageResult.Failed(FailureReason.ReadError, retryable = true)))
        val reader = reader(pages = 3, loader = loader)
        advanceUntilIdle()
        assertIs<PreparedPage.Error>(reader.prefetcher[PageView.One(0)]!!.pages.single())
        loader.failing.clear()
        reader.prefetcher.retry(0)
        advanceUntilIdle()
        assertIs<PreparedPage.Image<String>>(reader.prefetcher[PageView.One(0)]!!.pages.single())
        assertTrue(reader.prefetcher.failedPages.isEmpty())
    }

    @Test
    fun aPairCancelledHalfwayReleasesThePageItHadReady() = runTest {
        val loader = FakeLoader().apply { pageGates[1] = CompletableDeferred() }
        val reader = reader(pages = 8, config = spreadConfig(), viewport = LANDSCAPE, loader = loader)
        advanceUntilIdle()
        assertNull(reader.prefetcher[PageView.Two(0, 1)])
        reader.apply(ReaderEvent.GoTo(7))
        advanceUntilIdle()
        assertTrue(1 in loader.cancelled)
        assertTrue(reader.released.any { it.startsWith("0@") }, "released ${reader.released}")
    }

    @Test
    fun trimmingCancelsTheViewsBeingPreparedOffScreen() = runTest {
        val loader = FakeLoader().apply { gate = CompletableDeferred() }
        val reader = reader(pages = 8, start = 3, loader = loader)
        runCurrent()
        reader.prefetcher.trim()
        reader.apply(ReaderEvent.Tick(0))
        loader.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(3), reader.preparedPages())
    }

    @Test
    fun prefetchComesBackOnceTheReaderMovesAfterATrim() = runTest {
        val reader = reader(pages = 8, start = 3)
        advanceUntilIdle()
        reader.prefetcher.trim()
        reader.turn(Turn.Forward)
        advanceUntilIdle()
        assertEquals(setOf(3, 4, 5, 6), reader.preparedPages())
    }
}
