package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.source.ByteSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    private class Opened(val session: ArchiveSession, val archive: FakeArchive, val closed: CountDownLatch, val dir: File) : Closeable {
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

    private fun directory(pages: List<Pair<String, ByteArray>>, modified: Long?) =
        ExtractionCache.directory(temp.root, BookId("same-book-id"), pages.sumOf { it.second.size.toLong() }, modified)

    private fun open(pages: List<Pair<String, ByteArray>>, modified: Long?, beforeExtract: () -> Unit = {}): Opened {
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
        val dir = ExtractionCache.directory(temp.root, BookId("same-book-id"), source.size, source.lastModifiedMs)
        val result = runBlocking {
            withTimeout(5_000) {
                ArchiveSession.open(source, dir) {
                    val fake = FakeArchive(it, pages).also { made -> archive = made }
                    object : ArchiveHandle by fake {
                        override fun readData(sink: DataSink): Int {
                            beforeExtract()
                            return fake.readData(sink)
                        }
                    }
                }
            }
        }
        assertTrue("opens the archive: $result", result is OpenResult.Opened)
        return Opened((result as OpenResult.Opened).session, archive, closed, dir)
    }

    @Test
    fun `replacing an archive at the same book id with a new modified time reads the new pages`() {
        open(first, 1_000L).use { it.assertPages(first) }

        open(replacement, 2_000L).use {
            assertNotEquals(directory(first, 1_000L), it.dir)
            it.assertPages(replacement)
        }
    }

    @Test
    fun `a different archive size invalidates the cache even when the modified time is unchanged`() {
        open(first, 1_000L).use { it.assertPages(first) }
        val larger = replacement + ("003.jpg" to letters(100, 5))

        open(larger, 1_000L).use {
            assertNotEquals(directory(first, 1_000L), it.dir)
            it.assertPages(larger)
        }
    }

    @Test
    fun `the same size and modified time reuse the extracted files`() {
        open(first, 1_000L).use { it.assertPages(first) }

        open(first, 1_000L).use {
            assertEquals(directory(first, 1_000L), it.dir)
            it.assertPages(first)
            assertEquals("only listing opens the archive", 1, it.archive.opens.get())
        }
    }

    @Test
    fun `an input with no modified time reuses files of the same size`() {
        open(first, null).use { it.assertPages(first) }

        open(first, null).use {
            it.assertPages(first)
            assertEquals("only listing opens the archive", 1, it.archive.opens.get())
        }
    }

    @Test
    fun `an input with no modified time extracts again when its size changes`() {
        open(first, null).use { it.assertPages(first) }
        val smaller = listOf("001.jpg" to letters(300, 5))

        open(smaller, null).use {
            assertNotEquals(directory(first, null), it.dir)
            it.assertPages(smaller)
            assertFalse("the new generation has no old extra entry", it.dir.resolve("1").exists())
        }
        assertTrue("the old generation remains until idle cleanup", directory(first, null).resolve("1").isFile)
    }

    @Test
    fun `directory names are stable for the same size and time`() {
        val dir = directory(first, 1_000L)
        assertEquals(dir, directory(replacement, 1_000L))
        assertTrue(dir.name.matches(Regex("same-book-id-[0-9a-f]{16}")))
        assertNotEquals(dir, directory(first, null))
        assertEquals(directory(first, null), directory(replacement, null))
        assertFalse("naming does not create a directory", dir.exists())
    }

    @Test
    fun `an old session finishing after the new one cannot replace the new generation's pages`() {
        val extracting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = open(first, 1_000L) {
            extracting.countDown()
            assertTrue("old extraction released", release.await(5, TimeUnit.SECONDS))
        }
        try {
            assertTrue("old extraction is in progress", extracting.await(5, TimeUnit.SECONDS))
            open(replacement, 2_000L).use { current ->
                assertNotEquals(old.dir, current.dir)
                current.assertPages(replacement)
                release.countDown()
                old.assertPages(first)
                current.assertPages(replacement)
            }
        } finally {
            release.countDown()
            old.close()
        }
        open(replacement, 2_000L).use {
            it.assertPages(replacement)
            assertEquals("reopens only for listing", 1, it.archive.opens.get())
        }
    }
}
