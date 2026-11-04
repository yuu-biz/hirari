package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ShapeProbeTest {
    private val page = PageEntry(0, 0, byteArrayOf(), "0.jpg", 1024L * 1024, null)
    private class Bytes : PageBytes {
        val limits = mutableListOf<Int>()
        var fullReads = 0
        override suspend fun prefix(page: PageEntry, count: Int): PageResult<ByteArray> {
            limits += count
            return PageResult.Ready(ByteArray(count))
        }
        override suspend fun read(page: PageEntry): PageResult<ByteArray> {
            fullReads++
            return PageResult.Ready(ByteArray(1024 * 1024))
        }
        override fun close() = Unit
    }

    @Test
    fun aHeaderInTheFirstPrefixDoesNotReadTheRestOfTheImage() = runTest {
        val source = Bytes()
        val result = probeShape(OrderedPages(source), page) { PageResult.Ready(PageShape.Known(800, 1200)) }
        assertEquals(PageResult.Ready(PageShape.Known(800, 1200)), result)
        assertEquals(listOf(65536), source.limits)
        assertEquals(0, source.fullReads)
    }

    @Test
    fun aHeaderPastALargeAppSegmentUsesASecondBoundedProbe() = runTest {
        val source = Bytes()
        probeShape(OrderedPages(source), page) {
            if (it.size >= 100000) PageResult.Ready(PageShape.Known(800, 1200)) else corrupt
        }
        assertEquals(listOf(65536, 131072), source.limits)
        assertEquals(0, source.fullReads)
    }

    @Test
    fun anInconclusivePrefixFallsBackToTheWholeEntryOnlyOnce() = runTest {
        val source = Bytes()
        assertEquals(corrupt, probeShape(OrderedPages(source), page) { corrupt })
        assertEquals(listOf(65536, 131072), source.limits)
        assertEquals(1, source.fullReads)
    }

    @Test
    fun aCorruptStashedFallbackIsRemovedAndRefetchedOnce() = runTest {
        var reads = 0
        var drops = 0
        var cached: ByteArray? = null
        val source = object : PageBytes {
            override suspend fun prefix(page: PageEntry, count: Int): PageResult<ByteArray> = PageResult.Ready(ByteArray(count))
            override suspend fun read(page: PageEntry): PageResult<ByteArray> {
                reads++
                val bytes = ByteArray(1024 * 1024)
                if (reads == 1) cached = bytes
                return PageResult.Ready(bytes)
            }
            override fun fromStash(bytes: ByteArray) = bytes === cached
            override suspend fun invalidate(page: PageEntry) { drops++ }
            override fun close() = Unit
        }
        assertEquals(corrupt, probeShape(source, page) { corrupt })
        assertEquals(1, drops)
        assertEquals(2, reads)
    }
}
