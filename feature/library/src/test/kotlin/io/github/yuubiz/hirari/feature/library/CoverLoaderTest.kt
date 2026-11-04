package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbPriority
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbRequest
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbResult
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbSubject
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbnailKey
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoverLoaderTest {
    private val source = FakeThumbnails()
    private val memory = MemoryImages<CoverKey, String>(maxBytes = 100) { it.length }
    private val decoded = mutableListOf<File>()
    private val loader = CoverLoader(source, memory, Dispatchers.Unconfined) { file ->
        decoded += file
        "image:${file.name}"
    }

    private val spec = CoverSpec.Book(BookId("a"), Instant.EPOCH, 10, null, ReadingDirection.RightToLeft, CoverOrigin.Document(UriString("content://a")))
    private val key = spec.key(300)

    private fun request(priority: ThumbPriority = ThumbPriority.Visible) = ThumbRequest(
        ThumbSubject.Book(spec.id, spec.modified, spec.byteSize) { BookResult.Failed(BookFailure.NotFound) },
        300,
        spec.direction,
        priority,
    )

    private fun ready(name: String) = ThumbResult.Ready(File(name), ThumbnailKey(spec.id, spec.modified, spec.byteSize, null, 300, spec.direction))

    @Test
    fun `a visible transient failure is retried every sixty seconds without rebinding`() = runTest(UnconfinedTestDispatcher()) {
        source.result = ThumbResult.Unavailable
        val seen = mutableListOf<CoverState<String>>()
        val row = launch { loader.show(key, request()).collect { seen += it } }
        assertEquals(1, source.watches.size)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(1, source.watches.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, source.watches.size)
        source.result = ready("back.webp")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(CoverState.Loaded("image:back.webp"), seen.last())
        assertEquals(3, source.watches.size)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(3, source.watches.size)
        assertTrue(source.retries.isEmpty(), "the timer must respect source back-off")
        row.cancel()
    }

    @Test
    fun `a final failure is never retried by the timer and leaving cancels a transient retry`() = runTest(UnconfinedTestDispatcher()) {
        source.result = ThumbResult.Failed
        val finalRow = launch { loader.show(key, request()).collect {} }
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, source.watches.size)
        finalRow.cancel()
        source.result = ThumbResult.Unavailable
        val transientRow = launch { loader.show(key, request()).collect {} }
        transientRow.cancel()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, source.watches.size)
    }

    @Test
    fun `the stored thumbnail is decoded off the caller's thread`() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "cover-decode") }
        try {
            var decodedOn = ""
            val off = CoverLoader(source, memory, worker.asCoroutineDispatcher()) { file ->
                decodedOn = Thread.currentThread().name
                "image:${file.name}"
            }
            source.result = ready("a.webp")
            val state = runBlocking { off.show(key, request()).first() }
            assertEquals(CoverState.Loaded("image:a.webp"), state)
            assertTrue(decodedOn.startsWith("cover-decode"), decodedOn)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `a decoded cover stays in memory, so the next row needs neither the store nor a decode`() = runTest(UnconfinedTestDispatcher()) {
        source.result = ready("a.webp")
        val first = launch { loader.show(key, request()).collect {} }
        assertEquals("image:a.webp", loader.peek(key))
        first.cancel()

        val seen = mutableListOf<CoverState<String>>()
        val second = launch { loader.show(key, request()).collect { seen += it } }
        assertEquals(listOf<CoverState<String>>(CoverState.Loaded("image:a.webp")), seen)
        assertEquals(1, source.watches.size)
        assertEquals(1, decoded.size)
        second.cancel()
    }

    @Test
    fun `a row shown from memory holds its thumbnail in the store while it is bound`() = runTest(UnconfinedTestDispatcher()) {
        source.result = ready("a.webp")
        launch { loader.show(key, request()).collect {} }.cancel()

        val again = launch { loader.show(key, request()).collect {} }
        assertEquals(1, source.watches.size, "memory served the row")
        val held = source.holds.single { it.active }.request
        assertEquals(spec.id, held.subject.id)
        assertEquals(300, held.sizePx)
        again.cancel()
        assertTrue(source.holds.none { it.active })
    }

    @Test
    fun `cancelling the collector of a row that left releases the hold`() = runTest(UnconfinedTestDispatcher()) {
        source.result = ready("a.webp")
        val job = launch { loader.show(key, request()).collect {} }
        assertEquals(ThumbPriority.Visible, source.active().single().request.priority)
        job.cancel()
        job.join()
        assertTrue(source.active().isEmpty())
    }

    @Test
    fun `failed and unavailable results show the placeholder and a cover that cannot be decoded is unavailable`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<CoverState<String>>()
        source.result = ThumbResult.Failed
        launch { loader.show(key, request()).collect { seen += it } }.cancel()
        source.result = ThumbResult.Unavailable
        launch { loader.show(key, request()).collect { seen += it } }.cancel()
        val broken = CoverLoader(source, memory, Dispatchers.Unconfined) { null }
        source.result = ready("broken.webp")
        launch { broken.show(key, request()).collect { seen += it } }.cancel()
        assertEquals(listOf<CoverState<String>>(CoverState.Failed, CoverState.Unavailable, CoverState.Unavailable), seen)
        assertNull(memory[key])
    }

    @Test
    fun `an unavailable cover is asked for again at once when its place has its connection back`() = runTest(UnconfinedTestDispatcher()) {
        val recoveries = MutableSharedFlow<Unit>()
        val seen = mutableListOf<CoverState<String>>()
        source.result = ThumbResult.Unavailable
        val job = launch { loader.show(key, request(), recoveries).collect { seen += it } }
        source.result = ready("back.webp")
        recoveries.emit(Unit)
        assertEquals(listOf<CoverState<String>>(CoverState.Unavailable, CoverState.Loaded("image:back.webp")), seen)
        assertEquals(1, source.retries.size, "the cover was not tried without the back-off")
        assertTrue(source.watches.none { it.active }, "the first look kept its hold")
        recoveries.emit(Unit)
        assertEquals(1, source.retries.size, "a shown cover was looked at again")
        job.cancel()
    }

    @Test
    fun `a cover that fails again waits for the next time the connection comes back`() = runTest(UnconfinedTestDispatcher()) {
        val recoveries = MutableSharedFlow<Unit>()
        val seen = mutableListOf<CoverState<String>>()
        source.result = ThumbResult.Unavailable
        val job = launch { loader.show(key, request(), recoveries).collect { seen += it } }
        recoveries.emit(Unit)
        assertEquals(listOf<CoverState<String>>(CoverState.Unavailable, CoverState.Unavailable), seen)
        source.result = ready("later.webp")
        recoveries.emit(Unit)
        assertEquals(CoverState.Loaded("image:later.webp"), seen.last())
        assertEquals(2, source.retries.size)
        job.cancel()
    }

    @Test
    fun `refreshing a key makes the rows showing it look again and forgets the decoded cover`() = runTest(UnconfinedTestDispatcher()) {
        source.result = ready("old.webp")
        val seen = mutableListOf<CoverState<String>>()
        val job = launch { loader.show(key, request()).collect { seen += it } }
        assertEquals("image:old.webp", memory[key])

        source.result = ready("new.webp")
        loader.refresh(key)
        assertEquals(listOf<CoverState<String>>(CoverState.Loaded("image:old.webp"), CoverState.Loaded("image:new.webp")), seen)
        assertEquals(2, source.watches.size)
        assertEquals(1, source.active().size, "the first hold was released")

        loader.refresh(key.copy(sizePx = 400))
        assertEquals(2, source.watches.size, "another thumbnail is not looked at again")
        job.cancel()
    }

    @Test
    fun `warming decodes only the store and holds the cover without observing`() = runTest(UnconfinedTestDispatcher()) {
        source.result = ready("a.webp")
        val job = launch { loader.warm(key, request(ThumbPriority.Nearby)).collect {} }
        assertEquals(ThumbPriority.Nearby, source.cached.single().priority)
        assertTrue(source.watches.isEmpty())
        assertTrue(source.holds.single().active)
        assertEquals(listOf(File("a.webp")), decoded)
        assertEquals("image:a.webp", loader.peek(key))
        job.cancel()
        assertTrue(source.holds.none { it.active })
    }

    @Test
    fun `nearby work with no stored cover never starts generation`() = runTest(UnconfinedTestDispatcher()) {
        source.result = ThumbResult.Unavailable
        val job = launch { loader.warm(key, request(ThumbPriority.Nearby)).collect {} }
        assertEquals(1, source.cached.size)
        assertTrue(decoded.isEmpty())
        assertNull(loader.peek(key))
        assertTrue(source.watches.isEmpty())
        assertTrue(source.regenerated.isEmpty())
        job.cancel()
        assertTrue(source.holds.none { it.active })
    }

    @Test
    fun `cancelling nearby work before its IO turn releases the hold without decoding`() = runTest(UnconfinedTestDispatcher()) {
        source.result = ready("a.webp")
        val queued = CoverLoader(source, memory, StandardTestDispatcher(testScheduler)) {
            decoded += it
            "decoded"
        }
        val job = launch { queued.warm(key, request(ThumbPriority.Nearby)).collect {} }
        assertTrue(source.holds.single().active)
        job.cancel()
        runCurrent()
        assertTrue(decoded.isEmpty())
        assertNull(memory[key])
        assertTrue(source.holds.none { it.active })
    }

    @Test
    fun `queued visible decodes go ahead of nearby decodes`() = runTest(UnconfinedTestDispatcher()) {
        val nearbySource = object : ThumbnailSource by source {
            override suspend fun cached(request: ThumbRequest): ThumbResult = ready("nearby.webp")
        }
        val queued = CoverLoader(nearbySource, memory, StandardTestDispatcher(testScheduler)) {
            decoded += it
            "image:${it.name}"
        }
        val nearbyKey = key.copy(sizePx = 301)
        val nearby = launch { queued.warm(nearbyKey, request(ThumbPriority.Nearby)).collect {} }
        source.result = ready("visible.webp")
        val visible = launch { queued.show(key, request()).collect {} }
        runCurrent()
        assertEquals(listOf(File("visible.webp"), File("nearby.webp")), decoded)
        nearby.cancel()
        visible.cancel()
    }

    @Test
    fun `memory drops the least recently used past its limit and ignores what cannot fit`() {
        val images = MemoryImages<String, String>(maxBytes = 10) { it.length }
        images.put("a", "xxxx")
        images.put("b", "xxxx")
        assertEquals("xxxx", images["a"])
        images.put("c", "xxxx")
        assertNull(images["b"], "b was the least recently used")
        assertEquals("xxxx", images["a"])
        images.put("huge", "x".repeat(11))
        assertNull(images["huge"])
        images.put("a", "xx")
        images.remove("c")
        assertEquals(1, images.size)
    }

    @Test
    fun `two dense screens on each side are decoded from the store without generation`() = runTest(UnconfinedTestDispatcher()) {
        val images = MemoryImages<CoverKey, String>(100_000) { it.length }
        val cachedLoader = CoverLoader(source, images, Dispatchers.Unconfined) { "image:${it.name}" }
        source.result = ready("stored.webp")
        val refs = (0 until 220).map { CoverRef(spec.copy(id = BookId("book:$it")), 300) }
        val nearby = Prefetcher.forRefs(this) { ref ->
            val nearbyRequest = ThumbRequest(
                ThumbSubject.Book(ref.spec.id, spec.modified, spec.byteSize) { error("must not generate") },
                300, spec.direction, ThumbPriority.Nearby,
            )
            cachedLoader.warm(ref.key, nearbyRequest).collect {}
        }
        val visible = Prefetcher.forRefs(this) { awaitCancellation() }
        val viewport = CoverViewport(CoverFocus(), visible, nearby)
        viewport.update(refs, 72..107, scrolling = false)
        assertEquals(144, source.cached.size)
        assertEquals("image:stored.webp", images[refs[0].key])
        assertEquals("image:stored.webp", images[refs[179].key])
        assertNull(images[refs[180].key])
        assertTrue(source.cached.all { it.priority == ThumbPriority.Nearby })
        assertTrue(source.watches.isEmpty())
        assertTrue(source.retries.isEmpty())
        assertTrue(source.regenerated.isEmpty())
        viewport.clear()
        assertTrue(source.holds.none { it.active })
    }

    @Test
    fun `the prefetcher keeps what stays wanted, starts what is new and cancels what left`() {
        val started = mutableListOf<String>()
        val jobs = mutableMapOf<String, Job>()
        val prefetcher = Prefetcher<String> { key ->
            started += key
            CoroutineScope(Dispatchers.Unconfined).launch { awaitCancellation() }.also { jobs[key] = it }
        }
        prefetcher.update(setOf("a", "b", "c"))
        prefetcher.update(setOf("b", "c", "d"))
        assertEquals(listOf("a", "b", "c", "d"), started)
        assertTrue(jobs.getValue("a").isCancelled)
        assertFalse(jobs.getValue("b").isCancelled)
        prefetcher.clear()
        assertTrue(jobs.values.all { it.isCancelled })
        assertEquals(emptySet(), prefetcher.wanted)
    }

    @Test
    fun `the window around the visible items is two screenfuls either side within the list`() {
        assertEquals(0..39, nearbyWindow(10, 19, 100))
        assertEquals(0..14, nearbyWindow(0, 2, 100))
        assertEquals(22..45, nearbyWindow(34, 39, 46))
        assertTrue(nearbyWindow(0, -1, 10).isEmpty())
        assertTrue(nearbyWindow(0, 0, 0).isEmpty())
        assertEquals(99 + MAX_MARGIN, nearbyWindow(0, 99, 1000).last)
    }

    @Test
    fun trimmingKeepsTheImagesUsedMostRecentlyUnderTheTarget() {
        val images = MemoryImages<String, String>(maxBytes = 100) { it.length }
        images.put("a", "1234")
        images.put("b", "5678")
        images.put("c", "9012")
        images["a"]
        images.trimTo(8)
        assertEquals(null, images["b"])
        assertEquals("1234", images["a"])
        assertEquals("9012", images["c"])
        images.trimTo(0)
        assertEquals(0, images.size)
    }
}
