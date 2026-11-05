package io.github.yuubiz.hirari.core.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpTest {
    private val pages = (1..40).map { "%03d.jpg".format(it) to letters(1000 + it, it) }

    private fun open(format: ArchiveFormat, entries: List<Pair<String, ByteArray>> = pages, jumpOff: Int = 0): Pair<ArchiveSession, FakeArchive> {
        var handle: FakeArchive? = null
        val source = MemorySource(entries.flatMap { it.second.toList() }.toByteArray())
        val session = runBlocking {
            (ArchiveSession.open(source, null) { FakeArchive(it, entries, format, jumpOff).also { fake -> handle = fake } } as OpenResult.Opened).session
        }
        return session to handle!!
    }

    private fun ArchiveSession.data(index: Int) = runBlocking { (read(index) as EntryResult.Data).bytes }

    @Test
    fun readingARarBackwardReadsTwoHeadersAnEntryInsteadOfEveryHeaderBeforeIt() {
        val (session, archive) = open(ArchiveFormat.Rar5)
        session.use {
            val listed = archive.headers.get()
            for (i in pages.indices.reversed()) assertArrayEquals(pages[i].second, session.data(i))
            val read = archive.headers.get() - listed
            assertTrue("$read headers for ${pages.size} entries", read <= 2 * pages.size)
        }
    }

    @Test
    fun aPrefixThenTheWholeEntryJumpsBackToItsHeader() {
        val (session, archive) = open(ArchiveFormat.Rar)
        session.use {
            val listed = archive.headers.get()
            for (i in pages.indices) {
                runBlocking { session.readPrefix(i, 16) }
                assertArrayEquals(pages[i].second, session.data(i))
            }
            val read = archive.headers.get() - listed
            assertTrue("$read headers for ${pages.size} entries", read <= 3 * pages.size)
        }
    }

    @Test
    fun readingForwardStillWalksWithoutOpeningAgain() {
        val (session, archive) = open(ArchiveFormat.Rar5)
        session.use {
            assertArrayEquals(pages[0].second, session.data(0))
            val opens = archive.opens.get()
            for (i in 1 until pages.size) assertArrayEquals(pages[i].second, session.data(i))
            assertEquals(opens, archive.opens.get())
        }
    }

    @Test
    fun aFormatWithoutHeadersOfItsOwnWalksFromTheStart() {
        val (session, archive) = open(ArchiveFormat.Zip)
        session.use {
            val listed = archive.headers.get()
            assertArrayEquals(pages[30].second, session.data(30))
            assertEquals(31, archive.headers.get() - listed)
        }
    }

    @Test
    fun aJumpThatLandsOnAnotherHeaderFallsBackToWalkingFromTheStart() {
        val (session, archive) = open(ArchiveFormat.Rar5, jumpOff = 1)
        session.use {
            assertArrayEquals(pages[20].second, session.data(20))
            val opens = archive.opens.get()
            assertArrayEquals(pages[10].second, session.data(10))
            assertEquals("no second jump once one missed", opens + 1, archive.opens.get())
        }
    }
}
