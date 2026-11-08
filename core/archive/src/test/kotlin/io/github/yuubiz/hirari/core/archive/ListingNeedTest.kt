package io.github.yuubiz.hirari.core.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingNeedTest {
    private val entries = (1..30).map { i -> (if (i % 3 == 0) "%03d.txt" else "%03d.jpg").format(i) to letters(500 + i, i) }

    private val images = ListingNeed.Images(5) { String(it.rawName).endsWith(".jpg") }

    private fun open(format: ArchiveFormat, need: ListingNeed): Pair<ArchiveSession, FakeArchive> {
        var handle: FakeArchive? = null
        val source = MemorySource(entries.flatMap { it.second.toList() }.toByteArray())
        val session = runBlocking {
            val opened = ArchiveSession.open(source, null, { FakeArchive(it, entries, format).also { fake -> handle = fake } }, null, need)
            (opened as OpenResult.Opened).session
        }
        return session to handle!!
    }

    private fun names(session: ArchiveSession) = session.entries.map { String(it.rawName) }

    @Test
    fun aRarOrTarListingForACoverStopsAtTheFifthImageHeader() {
        for (format in listOf(ArchiveFormat.Rar, ArchiveFormat.Rar5, ArchiveFormat.Tar)) {
            val (session, archive) = open(format, images)
            session.use {
                assertEquals(format.name, listOf("001.jpg", "002.jpg", "003.txt", "004.jpg", "005.jpg", "006.txt", "007.jpg"), names(session))
                assertEquals(format.name, 7, archive.headers.get())
                assertTrue(format.name, session.partial)
                assertEquals(format.name, images, session.need)
                assertNull("a stop where the need is met is not an error", session.indexError)
            }
        }
    }

    @Test
    fun zipAnd7zAreListedToTheEndWhateverIsAsked() {
        for (format in listOf(ArchiveFormat.Zip, ArchiveFormat.SevenZip)) {
            for (need in listOf(images, ListingNeed.UpTo(2))) {
                val (session, _) = open(format, need)
                session.use {
                    assertEquals(format.name, entries.size, session.entries.size)
                    assertFalse(format.name, session.partial)
                    assertEquals(format.name, ListingNeed.All, session.need)
                }
            }
        }
    }

    @Test
    fun upToAnEntryReachesThatEntryAndNoFurther() {
        val (session, archive) = open(ArchiveFormat.Rar5, ListingNeed.UpTo(12))
        session.use {
            assertEquals(13, session.entries.size)
            assertEquals(13, archive.headers.get())
            assertTrue(session.partial)
            assertArrayEquals(entries[12].second, runBlocking { (session.read(12) as EntryResult.Data).bytes })
        }
    }

    @Test
    fun theLastEntryListedIsReadWithoutOpeningTheArchiveAgain() {
        val (session, archive) = open(ArchiveFormat.Rar, images)
        session.use {
            val opens = archive.opens.get()
            assertArrayEquals(entries[6].second, runBlocking { (session.read(6) as EntryResult.Data).bytes })
            assertEquals(opens, archive.opens.get())
            assertArrayEquals(entries[0].second, runBlocking { (session.read(0) as EntryResult.Data).bytes })
        }
    }

    @Test
    fun aNeedTheArchiveDoesNotMeetListsItToTheEnd() {
        for (need in listOf(ListingNeed.Images(50) { true }, ListingNeed.UpTo(100))) {
            val (session, _) = open(ArchiveFormat.Tar, need)
            session.use {
                assertEquals(entries.size, session.entries.size)
                assertFalse(session.partial)
                assertEquals("the need the listing went for, though it reached the end", need, session.need)
                assertNull(session.indexError)
            }
        }
    }

    @Test
    fun theReaderListsEverything() {
        val (session, _) = open(ArchiveFormat.Rar5, ListingNeed.All)
        session.use {
            assertEquals(entries.size, session.entries.size)
            assertFalse(session.partial)
        }
    }
}
