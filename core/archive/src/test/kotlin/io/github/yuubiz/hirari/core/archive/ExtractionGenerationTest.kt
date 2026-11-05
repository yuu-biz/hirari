package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.source.ByteSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExtractionGenerationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val first = listOf("001.jpg" to letters(800, 1), "002.jpg" to letters(500, 2))
    private val replacement = listOf("001.jpg" to letters(800, 3), "002.jpg" to letters(500, 4))

    private class Opened(val session: ArchiveSession, val archive: FakeArchive, val closed: CountDownLatch) : Closeable {
        fun assertPages(pages: List<Pair<String, ByteArray>>) = runBlocking {
            withTimeout(5_000) {
                for ((index, page) in pages.withIndex()) {
                    val result = session.read(index)
                    assertTrue("reads the data: $result", result is EntryResult.Data)
                    assertArrayEquals(page.second, (result as EntryResult.Data).bytes)
                }
            }
        }

        override fun close() {
            session.close()
            assertTrue("session released its source", closed.await(5, TimeUnit.SECONDS))
        }
    }

    private fun open(dir: File, pages: List<Pair<String, ByteArray>>, modified: Long?): Opened {
        val memory = MemorySource(pages.flatMap { it.second.toList() }.toByteArray())
        val closed = CountDownLatch(1)
        val source = object : ByteSource by memory {
            override val lastModifiedMs = modified

            override fun close() {
                memory.close()
                closed.countDown()
            }
        }
        lateinit var archive: FakeArchive
        val result = runBlocking {
            withTimeout(5_000) {
                ArchiveSession.open(source, dir) { FakeArchive(it, pages).also { made -> archive = made } }
            }
        }
        assertTrue("opens the archive: $result", result is OpenResult.Opened)
        return Opened((result as OpenResult.Opened).session, archive, closed)
    }

    @Test
    fun `replacing an archive at the same book id with a new modified time reads the new pages`() {
        val dir = temp.newFolder("same-book-id")
        open(dir, first, 1_000L).use { it.assertPages(first) }

        open(dir, replacement, 2_000L).use { it.assertPages(replacement) }
    }

    @Test
    fun `a different archive size invalidates the cache even when the modified time is unchanged`() {
        val dir = temp.newFolder("same-book-id")
        open(dir, first, 1_000L).use { it.assertPages(first) }
        val larger = replacement + ("003.jpg" to letters(100, 5))

        open(dir, larger, 1_000L).use { it.assertPages(larger) }
    }

    @Test
    fun `the same size and modified time reuse the extracted files`() {
        val dir = temp.newFolder("same-book-id")
        open(dir, first, 1_000L).use { it.assertPages(first) }

        open(dir, first, 1_000L).use {
            it.assertPages(first)
            assertEquals("only listing opens the archive", 1, it.archive.opens.get())
        }
    }

    @Test
    fun `an input with no modified time reuses files of the same size`() {
        val dir = temp.newFolder("same-book-id")
        open(dir, first, null).use { it.assertPages(first) }

        open(dir, first, null).use {
            it.assertPages(first)
            assertEquals("only listing opens the archive", 1, it.archive.opens.get())
        }
    }

    @Test
    fun `an input with no modified time extracts again when its size changes`() {
        val dir = temp.newFolder("same-book-id")
        open(dir, first, null).use { it.assertPages(first) }
        val smaller = listOf("001.jpg" to letters(300, 5))

        open(dir, smaller, null).use { it.assertPages(smaller) }
        assertFalse("obsolete entries are deleted too", dir.resolve("1").exists())
    }

    @Test
    fun `a cache with no generation stamp is extracted again`() {
        val dir = temp.newFolder("same-book-id")
        open(dir, first, 1_000L).use { it.assertPages(first) }
        assertTrue(dir.resolve(".generation").delete())

        open(dir, replacement, 1_000L).use { it.assertPages(replacement) }
    }

    @Test
    fun `a cache with an unreadable generation stamp is extracted again`() {
        val dir = temp.newFolder("same-book-id")
        open(dir, first, 1_000L).use { it.assertPages(first) }
        val stamp = dir.resolve(".generation")
        assertTrue(stamp.delete())
        assertTrue(stamp.mkdir())

        open(dir, replacement, 1_000L).use { it.assertPages(replacement) }
    }
}
