package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.model.BookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExtractionCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private val pages = listOf("001.jpg" to letters(3000, 1), "002.jpg" to letters(500, 2), "003.jpg" to letters(800, 3))
    private val now = 1_000_000_000_000L

    @After
    fun stop() {
        executor.shutdownNow()
    }

    private fun cache(root: File) = ExtractionCache(root) { now }

    private fun File.fill(name: String, size: Int, modified: Long = now): File = resolve(name).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(size))
        setLastModified(modified)
    }

    private fun extraction(dir: File, source: MemorySource = MemorySource(pages.flatMap { it.second.toList() }.toByteArray())): Extraction {
        val entries = pages.mapIndexed { i, (name, data) ->
            ArchiveEntry(i, name.toByteArray(), data.size.toLong(), isDirectory = false, encrypted = false, compression = null)
        }
        return Extraction(FakeArchive(source, pages), dispatcher, entries, dir, maxCacheBytes = 1L shl 30)
    }

    private fun Extraction.readNow(index: Int): EntryResult = runBlocking {
        withTimeout(5_000) {
            read(ArchiveEntry(index, pages[index].first.toByteArray(), pages[index].second.size.toLong(), false, false, null), ArchiveSession.MAX_ENTRY_BYTES)
        }
    }

    private fun assertData(index: Int, result: EntryResult) {
        assertTrue("reads the data: $result", result is EntryResult.Data)
        assertArrayEquals(pages[index].second, (result as EntryResult.Data).bytes)
    }

    @Test
    fun `clearing deletes the entry files of every book and reports their size`() {
        val root = temp.newFolder("extract")
        root.fill("book-a/0", 100)
        root.fill("book-a/1", 250)
        root.fill("book-b/0", 40)

        assertEquals(390L, cache(root).clear())

        assertEquals(emptyList<String>(), root.list()!!.toList())
        assertTrue(root.isDirectory)
    }

    @Test
    fun `clearing a cache that does not exist frees nothing`() {
        assertEquals(0L, cache(File(temp.root, "missing")).clear())
    }

    @Test
    fun `file edits discard all old generations and leave sibling books`() {
        val old = ExtractionCache.directory(temp.root, BookId("old"), 100, 1_000)
        val newer = ExtractionCache.directory(temp.root, BookId("old"), 100, 2_000)
        val sibling = ExtractionCache.directory(temp.root, BookId("sibling"), 100, 1_000)
        old.fill("0", 10)
        newer.fill("0", 20)
        sibling.fill("0", 30)
        ExtractionCache.under(temp.root).removeBooks(setOf(BookId("old")))
        assertFalse(old.exists())
        assertFalse(newer.exists())
        assertTrue(sibling.resolve("0").isFile)
    }

    @Test
    fun `clearing twice frees nothing the second time`() {
        val root = temp.newFolder("extract")
        root.fill("book/0", 10)

        assertEquals(10L, cache(root).clear())
        assertEquals(0L, cache(root).clear())
    }

    @Test
    fun `clearing recognises every generation directory of a book`() {
        val old = ExtractionCache.directory(temp.root, BookId("book"), 100, 1_000)
        val current = ExtractionCache.directory(temp.root, BookId("book"), 100, 2_000)
        old.fill("0", 10)
        current.fill("0", 20)

        assertEquals(30L, ExtractionCache.under(temp.root).clear())
        assertFalse(old.exists())
        assertFalse(current.exists())
    }

    @Test
    fun `the unfinished file of a running extraction stays and an abandoned one goes`() {
        val root = temp.newFolder("extract")
        val running = root.fill("book/entry-1-1.part", 70)
        val abandoned = root.fill("book/entry-2-2.part", 30, modified = now - 2 * 60 * 60 * 1000)
        root.fill("book/0", 5)

        assertEquals(35L, cache(root).clear())

        assertTrue(running.isFile)
        assertFalse(abandoned.exists())
        assertTrue(running.parentFile!!.isDirectory)
    }

    @Test
    fun `files that are no entries of the cache stay`() {
        val root = temp.newFolder("extract")
        val other = root.fill("book/notes.txt", 9)
        root.fill("book/0", 1)

        assertEquals(1L, cache(root).clear())

        assertTrue(other.isFile)
    }

    @Test
    fun `a book open while the cache is cleared reads every entry again`() {
        val dir = File(temp.newFolder("extract"), "book")
        val extraction = extraction(dir)
        assertData(2, extraction.readNow(2))
        assertEquals(3, dir.listFiles()!!.size)

        val freed = cache(dir.parentFile!!).clear()

        assertEquals(pages.sumOf { it.second.size }.toLong(), freed)
        assertFalse(dir.exists())
        assertData(2, extraction.readNow(2))
        assertData(0, extraction.readNow(0))
        assertData(1, extraction.readNow(1))
        extraction.close()
    }

    @Test
    fun `clearing while an extraction is under way loses no entry`() {
        val root = temp.newFolder("extract")
        val dir = File(root, "book")
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val source = MemorySource(pages.flatMap { it.second.toList() }.toByteArray()) {
            reading.countDown()
            resume.await()
        }
        val extraction = extraction(dir, source)
        val read = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { extraction.read(ArchiveEntry(2, ByteArray(0), pages[2].second.size.toLong(), false, false, null), ArchiveSession.MAX_ENTRY_BYTES) }
        }
        assertTrue(reading.await(5, TimeUnit.SECONDS))

        cache(root).clear()
        resume.countDown()

        assertData(2, runBlocking { read.await() })
        assertData(0, extraction.readNow(0))
        assertData(1, extraction.readNow(1))
        extraction.close()
    }
}
