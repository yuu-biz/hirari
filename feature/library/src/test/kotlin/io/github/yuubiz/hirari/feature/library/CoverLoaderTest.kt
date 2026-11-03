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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    fun `warming asks the source with the request it is given and decodes nothing`() = runTest(UnconfinedTestDispatcher()) {
        source.result = ready("a.webp")
        val job = launch { loader.warm(request(ThumbPriority.Nearby)).collect {} }
        assertEquals(ThumbPriority.Nearby, source.active().single().request.priority)
        assertTrue(decoded.isEmpty())
        job.cancel()
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
    fun `the window around the visible items is a screenful either side, within the list`() {
        assertEquals(0..29, nearbyWindow(10, 19, 100))
        assertEquals(0..8, nearbyWindow(0, 2, 100))
        assertEquals(28..45, nearbyWindow(34, 39, 46))
        assertTrue(nearbyWindow(0, -1, 10).isEmpty())
        assertTrue(nearbyWindow(0, 0, 0).isEmpty())
        assertEquals(99 + MAX_MARGIN, nearbyWindow(0, 99, 1000).last)
    }
}
