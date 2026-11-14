package io.github.yuubiz.hirari.core.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the header walk tells while a book opens (docs/spec.md section 4.8, 本を開いている間). */
class ListingProgressTest {
    private val pages = (1..20).map { "%03d.jpg".format(it) to letters(1000, it) }

    /** [FakeArchive] whose headers carry the position [at] gives. */
    private class Positioned(private val inner: FakeArchive, private val at: (Int) -> Long?) : ArchiveHandle by inner {
        private var count = 0

        override val header: Header?
            get() = inner.header?.let { Header(it.name, it.size, it.isDirectory, it.encrypted, it.formatName, it.format, it.filter, at(count - 1)) }

        override fun nextHeader(): Int = inner.nextHeader().also { if (it == Status.OK) count++ }

        override fun open(jump: Jump?): Int = inner.open(jump).also { count = 0 }
    }

    private fun walk(format: ArchiveFormat, positions: ((Int) -> Long?)? = null): List<Pair<Int, Float?>> {
        val told = ArrayList<Pair<Int, Float?>>()
        val source = MemorySource(pages.flatMap { it.second.toList() }.toByteArray())
        runBlocking {
            val opened = ArchiveSession.open(source, null, { FakeArchive(it, pages, format).let { fake -> if (positions == null) fake else Positioned(fake, positions) } }, password = null) { entries, fraction ->
                synchronized(told) { told += entries to fraction }
            }
            (opened as OpenResult.Opened).session.close()
        }
        return told
    }

    @Test
    fun `the count grows by one for each header the walk lists`() {
        assertEquals((1..pages.size).toList(), walk(ArchiveFormat.Rar5).map { it.first })
    }

    @Test
    fun `a format that walks its own headers tells how far into the archive it is`() {
        val fractions = walk(ArchiveFormat.Rar5).map { it.second ?: error("no fraction") }
        assertTrue(fractions.all { it in 0f..0.99f })
        assertEquals(0f, fractions.first(), 0f)
        assertTrue(fractions.zipWithNext().all { (a, b) -> b > a })
        assertEquals(1000f * (pages.size - 1) / (1000 * pages.size), fractions.last(), 0.001f)
    }

    @Test
    fun `the fraction stops at 99 percent`() {
        val fractions = walk(ArchiveFormat.Rar5) { 1_000_000_000L + it }.map { it.second }
        assertEquals(0.99f, fractions.last()!!, 0f)
    }

    @Test
    fun `a format whose positions are no progress tells the count alone`() {
        assertTrue(walk(ArchiveFormat.SevenZip).all { it.second == null })
    }

    @Test
    fun `headers that give no position leave the fraction out`() {
        val told = walk(ArchiveFormat.Rar5) { null }
        assertEquals(pages.size, told.size)
        assertTrue(told.all { it.second == null })
    }

    @Test
    fun `a position that does not come after the one before drops the fraction from there on`() {
        val told = walk(ArchiveFormat.Rar5) { if (it == 5) 100L else it * 1000L }
        assertNotNull(told[4].second)
        assertTrue(told.drop(5).all { it.second == null })
    }
}
