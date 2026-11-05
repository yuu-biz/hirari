package io.github.yuubiz.hirari.feature.reader.prefetch

import io.github.yuubiz.hirari.core.image.MarginScan
import io.github.yuubiz.hirari.core.image.PixelRect
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.TrimStrength
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import io.github.yuubiz.hirari.feature.reader.DEEP_BOTTOM
import io.github.yuubiz.hirari.feature.reader.LANDSCAPE
import io.github.yuubiz.hirari.feature.reader.PORTRAIT
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.feature.reader.ReaderConfig
import io.github.yuubiz.hirari.feature.reader.ReaderEvent
import io.github.yuubiz.hirari.feature.reader.ReaderState
import io.github.yuubiz.hirari.feature.reader.TALL
import io.github.yuubiz.hirari.feature.reader.scanOf
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.feature.reader.Viewport
import io.github.yuubiz.hirari.feature.reader.WIDE
import io.github.yuubiz.hirari.feature.reader.ZoomEvent
import io.github.yuubiz.hirari.feature.reader.spreadConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
    val shapes: MutableMap<Int, PageResult<PageShape.Known>> = mutableMapOf(),
    val failing: MutableMap<Int, PageResult<Nothing>> = mutableMapOf(),
    val rawFailing: MutableMap<Int, PageResult<Nothing>> = mutableMapOf(),
    val rawBytes: Long = 1000,
    /** What a page's data turns out to take once read; a provider that reports no size makes it differ. */
    val readRawBytes: Long = rawBytes,
) : PageLoader<String, String> {
    val cancelled = mutableListOf<Int>()
    var gate: CompletableDeferred<Unit>? = null

    /** Pages that wait here besides [gate]. */
    val pageGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
    var rawGate: CompletableDeferred<Unit>? = null

    /** How long each raw read takes, so a test can stop a run of reads at a moment. */
    var rawDelay = 0L
    val rawReads = mutableListOf<Int>()
    val stashed = mutableSetOf<Int>()
    val invalidated = mutableListOf<Int>()
    var repairOnInvalidate = false
    override fun fromStash(raw: String): Boolean = raw.startsWith("cached")
    override suspend fun invalidate(page: Int) {
        invalidated += page
        stashed.remove(page)
        if (repairOnInvalidate) failing.remove(page)
    }

    /** The raw data each prepared page was given, `null` for one read from the book. */
    val preparedFrom = mutableMapOf<Int, String?>()

    override suspend fun raw(page: Int): PageResult<String> {
        rawReads += page
        rawGate?.await()
        delay(rawDelay)
        return rawFailing[page] ?: PageResult.Ready(if (page in stashed) "cached$page" else "raw$page")
    }

    override fun bytesOf(raw: String): Long = readRawBytes

    override fun rawEstimate(page: Int): Long = rawBytes

    var corruptCachedShape = false
    override suspend fun shape(page: Int, raw: String?): PageResult<PageShape.Known> =
        if (corruptCachedShape && raw != null && fromStash(raw)) PageResult.Failed(FailureReason.Corrupt, false)
        else shapes[page] ?: PageResult.Ready(TALL)

    /** The margins each page reads as, and how many times each was read. */
    val margins = mutableMapOf<Int, MarginScan>()
    val marginReads = mutableMapOf<Int, Int>()
    val prepares = mutableMapOf<Int, Int>()

    /** Fails a page at the sizes this names, the way a lost connection fails a read for a zoom. */
    var failWhen: (page: Int, size: PixelSize) -> PageResult<Nothing>? = { _, _ -> null }

    override suspend fun prepare(page: Int, size: PixelSize, steps: PageSteps, raw: String?, readMargins: Boolean): PageResult<MadePage<String>> {
        preparedFrom[page] = raw
        try {
            gate?.await()
            pageGates[page]?.await()
            delay(1)
        } catch (e: CancellationException) {
            cancelled += page
            throw e
        }
        prepares[page] = (prepares[page] ?: 0) + 1
        failing[page]?.let { return it }
        failWhen(page, size)?.let { return it }
        if (readMargins) marginReads[page] = (marginReads[page] ?: 0) + 1
        return PageResult.Ready(MadePage("$page@${size.width}x${size.height}", margins[page]?.takeIf { readMargins }))
    }
}

/** The reader and its prefetcher as the screen wires them: a new state that changes what is laid out or shown makes a new plan. */
private class Reader(
    scope: CoroutineScope,
    var state: ReaderState,
    val loader: FakeLoader = FakeLoader(),
    budgetBytes: Long = 1L shl 30,
    val autoDraw: Boolean = true,
) : Prefetcher.Listener {
    val released = mutableListOf<String>()

    /** Views the screen draws besides the ones [state] names. */
    var alsoShown: Set<PageView> = emptySet()
    val prefetcher = Prefetcher(scope, loader, { released += it }, budgetBytes, PrefetchDepths(), this)

    init {
        prefetcher.update(state.prefetchInput())
    }

    override fun onShape(page: Int, shape: PageShape.Known) = apply(ReaderEvent.ShapeLearned(page, shape))

    override fun onChanged() {
        if (autoDraw) prefetcher.drawn(state.current)
    }

    override fun onMargins(page: Int, scan: MarginScan) = apply(ReaderEvent.MarginsLearned(page, scan))

    fun apply(event: ReaderEvent) {
        val before = state
        state = state.reduce(event)
        if (!state.prefetchChangedFrom(before)) return
        val input = state.prefetchInput()
        prefetcher.update(
            PrefetchInput(input.views, input.current, input.shapes, input.travel, input.targets, input.shown + alsoShown, readsMargins = input.readsMargins, pinching = input.pinching),
        )
    }

    /** A pinch that grows the span by [ratio] once it starts, and lets go. */
    fun pinch(ratio: Float) {
        pinching(ratio)
        lift()
    }

    /** Two fingers that have changed the zoom by [ratio] and are still down. */
    fun pinching(ratio: Float) {
        apply(ReaderEvent.Zoom(ZoomEvent.FingersDown(100f)))
        apply(ReaderEvent.Zoom(ZoomEvent.SpanChanged(120f)))
        apply(ReaderEvent.Zoom(ZoomEvent.SpanChanged(120f * ratio)))
    }

    fun lift() = apply(ReaderEvent.Zoom(ZoomEvent.FingersUp))

    fun turn(turn: Turn) {
        apply(ReaderEvent.Act(ReaderAction.TurnView(turn), 0))
        apply(ReaderEvent.Tick(Long.MAX_VALUE))
    }

    fun preparedPages(): Set<Int> = state.pagination.views.filter { prefetcher[it] != null }.flatMap { it.pages }.toSet()

    fun rawPages(): Set<Int> = state.pagination.shapes.indices.filter { prefetcher.raw(it) != null }.toSet()
}

private fun TestScope.reader(
    pages: Int,
    config: ReaderConfig = SINGLE,
    viewport: Viewport = PORTRAIT,
    start: Int = 0,
    loader: FakeLoader = FakeLoader(),
    budgetBytes: Long = 1L shl 30,
    autoDraw: Boolean = true,
) = Reader(CoroutineScope(StandardTestDispatcher(testScheduler)), ReaderState.open(pages, start, config, viewport), loader, budgetBytes, autoDraw)

class PrefetcherTest {
    @Test
    fun prefetchBeyondTheNextViewWaitsForDrawingEvenAfterPreparation() = runTest {
        val reader = reader(pages = 60, start = 10, autoDraw = false)
        advanceUntilIdle()
        assertEquals(setOf(10, 11), reader.loader.rawReads.toSet())
        assertEquals(setOf(10, 11), reader.preparedPages())
        assertTrue(!reader.prefetcher.canPrefetch)
        reader.prefetcher.drawn(reader.state.current)
        advanceUntilIdle()
        assertTrue(reader.prefetcher.canPrefetch)
        assertTrue(12 in reader.loader.rawReads)
        assertTrue(9 in reader.loader.rawReads)
    }

    @Test
    fun aSettledZoomPreparesTheCurrentViewAgainKeepingTheOldUntilThenAndALaterZoomDropsTheFirst() = runTest {
        val scan = PageShape.Known(3000, 4500)
        val loader = FakeLoader(shapes = (0 until 3).associateWith { PageResult.Ready(scan) }.toMutableMap())
        val reader = reader(pages = 3, loader = loader)
        advanceUntilIdle()
        val first = PageView.One(0)
        assertEquals(PreparedPage.Image("0@1080x1620"), reader.prefetcher[first]!!.pages.single())
        val gate = CompletableDeferred<Unit>()
        loader.pageGates[0] = gate
        reader.pinch(1.5625f)
        advanceUntilIdle()
        assertEquals(PreparedPage.Image("0@1080x1620"), reader.prefetcher[first]!!.pages.single(), "the old page draws until the new one is ready")
        reader.pinch(0.81f)
        advanceUntilIdle()
        assertTrue(0 in loader.cancelled, "the read for the first zoom stops")
        gate.complete(Unit)
        advanceUntilIdle()
        val prepared = reader.prefetcher[first]!!
        assertEquals(reader.state.prefetchTarget(first), prepared.target)
        assertTrue(prepared.target.sizes.single().width in 1081 until 2109, "the second zoom, 1.42 times: ${prepared.target.sizes}")
        assertEquals(2, loader.prepares[0], "the first zoom was never made")
        assertEquals(listOf("0@1080x1620"), reader.released)
        assertEquals(PreparedPage.Image("1@1080x1620"), reader.prefetcher[PageView.One(1)]!!.pages.single(), "the next view stays at the scale mode")
    }

    @Test
    fun aZoomThatFailsToReadLeavesThePreparedPageReadableAndTheScaleModeUntouched() = runTest {
        val scan = PageShape.Known(3000, 4500)
        val loader = FakeLoader(shapes = (0 until 3).associateWith { PageResult.Ready(scan) }.toMutableMap())
        val reader = reader(pages = 3, loader = loader)
        advanceUntilIdle()
        val first = PageView.One(0)
        val before = reader.prefetcher[first]!!
        loader.failWhen = { _, size -> if (size.width > 1080) PageResult.Failed(FailureReason.ConnectionLost, retryable = true) else null }
        reader.pinch(1.5625f)
        advanceUntilIdle()
        assertTrue(reader.prefetcher[first] === before, "the page that was readable still is")
        assertEquals(emptyList(), reader.released)
        assertEquals(emptyMap(), reader.prefetcher.failedPages)
        val reads = loader.prepares[0]
        reader.prefetcher.update(reader.state.prefetchInput())
        advanceUntilIdle()
        assertEquals(reads, loader.prepares[0], "the failed zoom is not read again by every plan")
        reader.pinch(0.4f)
        advanceUntilIdle()
        assertEquals(1f, reader.state.zoom.factor)
        assertEquals(PreparedPage.Image("0@1080x1620"), reader.prefetcher[first]!!.pages.single(), "back at the scale mode the page is not an error")
        assertEquals(reads, loader.prepares[0])
    }

    @Test
    fun aZoomThatFailedIsReadAgainWhenTheConnectionComesBack() = runTest {
        val scan = PageShape.Known(3000, 4500)
        val loader = FakeLoader(shapes = (0 until 3).associateWith { PageResult.Ready(scan) }.toMutableMap())
        val reader = reader(pages = 3, loader = loader)
        advanceUntilIdle()
        loader.failWhen = { _, size -> if (size.width > 1080) PageResult.Failed(FailureReason.ConnectionLost, retryable = true) else null }
        reader.pinch(1.5625f)
        advanceUntilIdle()
        assertEquals(PreparedPage.Image("0@1080x1620"), reader.prefetcher[PageView.One(0)]!!.pages.single())
        loader.failWhen = { _, _ -> null }
        reader.prefetcher.retryLost()
        advanceUntilIdle()
        assertEquals(PreparedPage.Image("0@2109x3164"), reader.prefetcher[PageView.One(0)]!!.pages.single())
    }

    @Test
    fun aPinchStopsTheReadForTheLastZoomAndItsResultIsNotUsed() = runTest {
        val scan = PageShape.Known(3000, 4500)
        val loader = FakeLoader(shapes = (0 until 3).associateWith { PageResult.Ready(scan) }.toMutableMap())
        val reader = reader(pages = 3, loader = loader)
        advanceUntilIdle()
        val first = PageView.One(0)
        val gate = CompletableDeferred<Unit>()
        loader.pageGates[0] = gate
        reader.pinch(1.5625f)
        advanceUntilIdle()
        assertEquals(emptyList(), loader.cancelled, "the read for the zoom is under way")
        reader.pinching(0.9f)
        advanceUntilIdle()
        assertTrue(0 in loader.cancelled, "the second pinch stops it at once, with the fingers still down")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PreparedPage.Image("0@1080x1620"), reader.prefetcher[first]!!.pages.single(), "its result was not used")
        assertEquals(1, loader.prepares[0] ?: 0, "and nothing new starts while the fingers are down")
        loader.pageGates.clear()
        reader.lift()
        advanceUntilIdle()
        assertEquals(reader.state.prefetchTarget(first), reader.prefetcher[first]!!.target)
        assertTrue(reader.prefetcher[first]!!.target.zoomed)
    }

    @Test
    fun failedFirstViewReleasesPrefetchWithoutDrawing() = runTest {
        val error = PageResult.Failed(io.github.yuubiz.hirari.core.model.FailureReason.ReadError, retryable = true)
        val reader = reader(pages = 60, loader = FakeLoader(rawFailing = mutableMapOf(0 to error)), autoDraw = false)
        advanceUntilIdle()
        assertTrue(reader.prefetcher.canPrefetch)
        assertTrue(2 in reader.loader.rawReads)
    }

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
        assertEquals(setOf(0, 1), loader.cancelled.toSet())
        loader.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(6, 7), reader.preparedPages())
    }

    @Test
    fun aPageWhoseHeaderFailsPairsAsPortraitAndItsPartnerStillDraws() = runTest {
        val notAnImage = PageResult.Unsupported(UnsupportedKind.NotAnImage)
        val reader = reader(pages = 4, config = spreadConfig(), viewport = LANDSCAPE, loader = FakeLoader(shapes = mutableMapOf(1 to notAnImage)))
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
    fun aPageThatFailedWithSharpenIsReadAgainOnceSharpenIsOff() = runTest {
        val loader = FakeLoader(failing = mutableMapOf(0 to PageResult.Failed(FailureReason.ReadError, retryable = true)))
        val reader = reader(pages = 3, config = SINGLE.copy(adjust = ImageAdjust(sharpen = true)), loader = loader)
        advanceUntilIdle()
        assertIs<PreparedPage.Error>(reader.prefetcher[PageView.One(0)]!!.pages.single())
        loader.failing.clear()
        reader.turn(Turn.Forward)
        reader.turn(Turn.Back)
        advanceUntilIdle()
        assertIs<PreparedPage.Error>(reader.prefetcher[PageView.One(0)]!!.pages.single())
        reader.apply(ReaderEvent.ConfigChanged(reader.state.config.copy(adjust = ImageAdjust())))
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

    @Test
    fun theRawDataOfThePagesInReachIsHeldAndViewsDecodeFromIt() = runTest {
        val reader = reader(pages = 60, start = 20)
        advanceUntilIdle()
        assertEquals((4..52).toSet(), reader.rawPages())
        assertEquals("raw20", reader.loader.preparedFrom[20])
        assertEquals("raw22", reader.loader.preparedFrom[22])
        assertEquals(reader.loader.rawReads.size, reader.loader.rawReads.toSet().size, "read twice: ${reader.loader.rawReads}")
    }

    @Test
    fun aViewWaitsForTheRawReadOfItsPageInsteadOfReadingItAgain() = runTest {
        val loader = FakeLoader().apply { rawGate = CompletableDeferred() }
        val reader = reader(pages = 3, loader = loader)
        runCurrent()
        assertNull(reader.prefetcher[PageView.One(0)])
        loader.rawGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("raw0", loader.preparedFrom[0])
    }

    @Test
    fun rawDataTakesAtMostHalfTheBudgetNearestFirst() = runTest {
        val reader = reader(pages = 60, viewport = Viewport(10f, 15f, 1f), start = 20, budgetBytes = 20_000)
        advanceUntilIdle()
        assertEquals(10, reader.rawPages().size, "held ${reader.rawPages()}")
        assertTrue(reader.rawPages().containsAll(listOf(19, 20, 21, 22)), "held ${reader.rawPages()}")
    }

    @Test
    fun rawDataLargerThanItsEstimateIsDroppedFarthestFirstAndNotReadAgain() = runTest {
        val loader = FakeLoader(rawBytes = 1000, readRawBytes = 3000).apply { rawDelay = 1 }
        val reader = reader(pages = 60, viewport = Viewport(10f, 15f, 1f), start = 20, loader = loader, budgetBytes = 20_000)
        advanceTimeBy(10_000)
        assertEquals(setOf(19, 20, 21), reader.rawPages())
        assertEquals(1, loader.rawReads.count { it == 22 }, "reads ${loader.rawReads}")
    }

    @Test
    fun aJumpDropsTheRawDataOutOfReach() = runTest {
        val reader = reader(pages = 200)
        advanceUntilIdle()
        assertTrue(0 in reader.rawPages())
        reader.apply(ReaderEvent.GoTo(150))
        advanceUntilIdle()
        assertEquals((134..182).toSet(), reader.rawPages())
    }

    @Test
    fun aPageWhoseRawReadFailsIsAnErrorAndIsNotReadAgainUntilARetry() = runTest {
        val lost = PageResult.Failed(FailureReason.ConnectionLost, retryable = true)
        val loader = FakeLoader(rawFailing = mutableMapOf(1 to lost))
        val reader = reader(pages = 4, loader = loader)
        advanceUntilIdle()
        reader.turn(Turn.Forward)
        advanceUntilIdle()
        assertEquals(PreparedPage.Error(lost), reader.prefetcher[PageView.One(1)]!!.pages.single())
        assertEquals(1, loader.rawReads.count { it == 1 })
        loader.rawFailing.clear()
        reader.prefetcher.retry(1)
        advanceUntilIdle()
        assertIs<PreparedPage.Image<String>>(reader.prefetcher[PageView.One(1)]!!.pages.single())
        assertEquals("raw1", loader.preparedFrom[1])
    }

    @Test
    fun eachPageReadsItsMarginsOnceAndIsPreparedAgainAsTheTrimKeepsIt() = runTest {
        val loader = FakeLoader().apply { (0 until 4).forEach { margins[it] = DEEP_BOTTOM } }
        val reader = reader(pages = 4, config = SINGLE.copy(trim = TrimStrength.Strongest), loader = loader)
        advanceUntilIdle()
        assertEquals(setOf(0, 1, 2), reader.preparedPages())
        assertEquals(mapOf(0 to 1, 1 to 1, 2 to 1), loader.marginReads)
        val prepared = reader.prefetcher[PageView.One(0)]!!
        assertEquals(reader.state.prefetchTarget(PageView.One(0)), prepared.target)
        assertEquals(0.8f, prepared.target.steps.single().part.bottom, 0.002f)

        reader.apply(ReaderEvent.ConfigChanged(reader.state.config.copy(trim = TrimStrength.Weak)))
        advanceUntilIdle()
        assertEquals(mapOf(0 to 1, 1 to 1, 2 to 1), loader.marginReads)
        assertEquals(0.88f, reader.prefetcher[PageView.One(0)]!!.target.steps.single().part.bottom, 0.002f)
    }

    @Test
    fun turningTrimOnReadsTheMarginsOfTheViewsAlreadyPrepared() = runTest {
        val loader = FakeLoader().apply { (0 until 3).forEach { margins[it] = DEEP_BOTTOM } }
        val reader = reader(pages = 3, loader = loader)
        advanceUntilIdle()
        assertTrue(loader.marginReads.isEmpty())
        reader.apply(ReaderEvent.ConfigChanged(reader.state.config.copy(trim = TrimStrength.Strongest)))
        advanceUntilIdle()
        assertEquals(mapOf(0 to 1, 1 to 1, 2 to 1), loader.marginReads)
        assertEquals(0.8f, reader.prefetcher[PageView.One(0)]!!.target.steps.single().part.bottom, 0.002f)
    }

    @Test
    fun turningTrimOnWhileViewsArePreparedReadsTheirMargins() = runTest {
        val loader = FakeLoader().apply {
            gate = CompletableDeferred()
            (0 until 3).forEach { margins[it] = DEEP_BOTTOM }
        }
        val reader = reader(pages = 3, loader = loader)
        runCurrent()
        reader.apply(ReaderEvent.ConfigChanged(reader.state.config.copy(trim = TrimStrength.Strongest)))
        loader.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(mapOf(0 to 1, 1 to 1, 2 to 1), loader.marginReads)
        assertEquals(0.8f, reader.prefetcher[PageView.One(0)]!!.target.steps.single().part.bottom, 0.002f)
    }

    @Test
    fun aPageWithNothingToCutIsNotPreparedAgainOnceItsMarginsAreRead() = runTest {
        val bleeding = scanOf(PixelSize(300, 450), PixelRect(0, 0, 300, 450))
        val loader = FakeLoader().apply { margins[0] = bleeding }
        val reader = reader(pages = 1, config = SINGLE.copy(trim = TrimStrength.Strongest), loader = loader)
        advanceUntilIdle()
        assertEquals(mapOf(0 to 1), loader.marginReads)
        assertEquals(1, loader.prepares[0])
    }

    @Test
    fun trimmingKeepsOnlyTheRawDataOfTheViewsOnScreen() = runTest {
        val reader = reader(pages = 60, start = 20)
        advanceUntilIdle()
        reader.prefetcher.trim()
        assertEquals(setOf(20), reader.rawPages())
    }

    @Test
    fun theConnectionComingBackReadsThePagesItLostOnceAndNoOthers() = runTest {
        val lost = PageResult.Failed(FailureReason.ConnectionLost, retryable = true)
        val corrupt = PageResult.Failed(FailureReason.Corrupt, retryable = false)
        val loader = FakeLoader(rawFailing = mutableMapOf(0 to lost, 1 to lost, 2 to corrupt))
        val reader = reader(pages = 4, loader = loader)
        advanceUntilIdle()
        assertEquals(setOf(0, 1, 2), reader.prefetcher.failedPages.keys)
        loader.rawFailing.remove(0)
        reader.prefetcher.retryLost()
        advanceUntilIdle()
        assertIs<PreparedPage.Image<String>>(reader.prefetcher[PageView.One(0)]!!.pages.single())
        assertEquals(mapOf(1 to lost, 2 to corrupt), reader.prefetcher.failedPages)
        assertEquals(listOf(2, 2, 1), listOf(0, 1, 2).map { page -> loader.rawReads.count { it == page } })
    }

    @Test
    fun theConnectionComingBackReadsTheShapeItLostAndLaysThePageOutByIt() = runTest {
        val lost = PageResult.Failed(FailureReason.ConnectionLost, retryable = true)
        val loader = FakeLoader(shapes = mutableMapOf(1 to lost))
        val reader = reader(pages = 4, config = spreadConfig(), viewport = LANDSCAPE, loader = loader)
        advanceUntilIdle()
        assertEquals(FAILED_PAGE_SHAPE, reader.state.pagination.shapes[1])
        loader.shapes[1] = PageResult.Ready(WIDE)
        reader.prefetcher.retryLost()
        advanceUntilIdle()
        assertEquals(WIDE, reader.state.pagination.shapes[1])
        val view = reader.state.pagination.views.first { 1 in it.pages }
        assertTrue(reader.prefetcher[view]!!.pages.all { it is PreparedPage.Image }, "view $view")
        assertTrue(reader.prefetcher.failedPages.isEmpty())
    }

    @Test
    fun aShapeLostAgainAfterTheConnectionCameBackShowsItsErrorCard() = runTest {
        val lost = PageResult.Failed(FailureReason.ConnectionLost, retryable = true)
        val loader = FakeLoader(shapes = mutableMapOf(1 to lost))
        val reader = reader(pages = 4, config = spreadConfig(), viewport = LANDSCAPE, loader = loader)
        advanceUntilIdle()
        reader.prefetcher.retryLost()
        advanceUntilIdle()
        assertEquals(FAILED_PAGE_SHAPE, reader.state.pagination.shapes[1])
        assertEquals(PreparedPage.Error(lost), reader.prefetcher[PageView.Two(0, 1)]!!.pages[1])
    }

    @Test
    fun nothingIsReadAgainWhenNoPageWasLost() = runTest {
        val loader = FakeLoader(rawFailing = mutableMapOf(1 to PageResult.Failed(FailureReason.ReadError, retryable = true)))
        val reader = reader(pages = 3, loader = loader)
        advanceUntilIdle()
        val reads = loader.rawReads.size
        reader.prefetcher.retryLost()
        advanceUntilIdle()
        assertEquals(reads, loader.rawReads.size)
    }

    @Test
    fun aCorruptStashedPageIsInvalidatedAndFetchedFreshOnce() = runTest {
        val loader = FakeLoader(failing = mutableMapOf(0 to PageResult.Failed(FailureReason.Corrupt, false)))
        loader.stashed += 0
        loader.repairOnInvalidate = true
        val reader = reader(pages = 3, loader = loader)
        advanceUntilIdle()
        assertEquals(listOf(0), loader.invalidated)
        assertEquals(2, loader.rawReads.count { it == 0 })
        assertEquals("raw0", loader.preparedFrom[0])
        assertTrue(reader.prefetcher.failedPages[0] == null)
    }

    @Test
    fun aFreshPageThatAlsoFailsIsNotAutomaticallyFetchedAgainAndRetryDropsItsRawBytes() = runTest {
        val loader = FakeLoader(failing = mutableMapOf(0 to PageResult.Failed(FailureReason.Corrupt, false)))
        loader.stashed += 0
        val reader = reader(pages = 3, loader = loader)
        advanceUntilIdle()
        assertEquals(2, loader.rawReads.count { it == 0 })
        assertEquals(listOf(0), loader.invalidated)
        reader.prefetcher.retry(0)
        advanceUntilIdle()
        assertEquals(listOf(0, 0), loader.invalidated)
        assertEquals(3, loader.rawReads.count { it == 0 })
    }

    @Test
    fun pausingCancelsMemoryPrefetchAndReturningResumesFromTheSameView() = runTest {
        val loader = FakeLoader().apply { rawDelay = 100 }
        val reader = reader(pages = 60, loader = loader)
        advanceTimeBy(10)
        reader.prefetcher.onStop()
        advanceUntilIdle()
        val pausedReads = loader.rawReads.toList()
        advanceTimeBy(1000)
        assertEquals(pausedReads, loader.rawReads)
        reader.prefetcher.onStart()
        advanceUntilIdle()
        assertTrue(loader.rawReads.size > pausedReads.size)
    }

    @Test
    fun aCorruptCachedHeaderAlsoGetsOneFreshReadBeforeLayoutFails() = runTest {
        val loader = FakeLoader().apply { stashed += 0; corruptCachedShape = true }
        val reader = reader(pages = 3, loader = loader)
        advanceUntilIdle()
        assertEquals(listOf(0), loader.invalidated)
        assertEquals(2, loader.rawReads.count { it == 0 })
        assertTrue(reader.prefetcher.failedPages.isEmpty())
    }

}
