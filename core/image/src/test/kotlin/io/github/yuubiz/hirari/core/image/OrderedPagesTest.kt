package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.source.smb.SmbPrefetchCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedPagesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class Archive : PageBytes {
        val started = mutableListOf<Int>()
        var active = 0
        var peak = 0
        override suspend fun read(page: PageEntry): PageResult<ByteArray> {
            started += page.entryIndex
            active++
            peak = maxOf(peak, active)
            try {
                delay(100L + page.entryIndex * 7)
                return PageResult.Ready(byteArrayOf(page.entryIndex.toByte()))
            } finally {
                active--
            }
        }
        override fun close() = Unit
    }

    private fun page(index: Int) = PageEntry(index, index, byteArrayOf(), "$index.jpg", 1, null)

    @Test
    fun fortyRequestsReadPageOneFirstAndThenInReadingOrderWithAtMostTwoInFlight() = runTest {
        val archive = Archive()
        val pages = OrderedPages(archive)
        pages.prioritize((0..39).toList())
        val requests = (39 downTo 0).map { index -> async { pages.read(page(index)) } }
        runCurrent()
        assertEquals(listOf(0), archive.started)
        advanceUntilIdle()
        requests.forEach { it.await() }
        assertEquals((0..39).toList(), archive.started)
        assertTrue(archive.peak <= 2)
    }

    @Test
    fun aTurnMovesTheNewViewAheadOfQueuedReadsAndDropsCancelledRequests() = runTest {
        val archive = Archive()
        val pages = OrderedPages(archive)
        pages.prioritize((0..39).toList())
        val requests = (0..39).map { index -> async { pages.read(page(index)) } }
        runCurrent()
        advanceTimeBy(50)
        pages.prioritize(listOf(20, 21) + (22..39) + (19 downTo 0))
        requests[1].cancel()
        advanceUntilIdle()
        assertEquals(listOf(0, 20, 21), archive.started.take(3))
        assertFalse(1 in archive.started)
        assertTrue(archive.peak <= 2)
    }

    @Test
    fun cancellingTheActiveReadReleasesTheQueue() = runTest {
        val archive = Archive()
        val pages = OrderedPages(archive)
        val first = async { pages.read(page(0)) }
        val next = async { pages.read(page(1)) }
        runCurrent()
        first.cancel()
        advanceUntilIdle()
        assertTrue(next.await() is PageResult.Ready)
        assertEquals(listOf(0, 1), archive.started)
        assertEquals(1, archive.peak)
    }

    @Test
    fun diskFillsAndRawReadsShareTheOrderBeforeLookingInTheStash() = runTest {
        val archive = Archive()
        val cache = SmbPrefetchCache(temp.root.resolve("stash"), reserveBytes = 0)
        val pages = StashedPages(archive, DiskStash(cache, BookId("ordered"), 1000, Instant.EPOCH))
        pages.prioritize((0..39).toList())
        val requests = (39 downTo 0).map { index ->
            async {
                if (index % 2 == 0) pages.read(page(index)) else pages.fill(page(index))
            }
        }
        requests.forEach { it.await() }
        assertEquals((0..39).toList(), archive.started)
        assertEquals(1, archive.peak)
    }
}
