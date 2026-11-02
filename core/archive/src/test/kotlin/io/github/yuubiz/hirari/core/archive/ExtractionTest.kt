package io.github.yuubiz.hirari.core.archive

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExtractionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private val pages = listOf("001.jpg" to letters(3000, 1), "002.jpg" to letters(500, 2), "003.jpg" to letters(800, 3))

    @After
    fun stop() {
        executor.shutdownNow()
    }

    private fun entries(pages: List<Pair<String, ByteArray>>) = pages.mapIndexed { i, (name, data) ->
        ArchiveEntry(i, name.toByteArray(), data.size.toLong(), isDirectory = false, encrypted = false, compression = null)
    }

    private fun extraction(
        dir: File,
        source: MemorySource = MemorySource(pages.flatMap { it.second.toList() }.toByteArray()),
        maxCacheBytes: Long = 1L shl 30,
    ) = Extraction(FakeArchive(source, pages), dispatcher, entries(pages), dir, maxCacheBytes = maxCacheBytes)

    private fun Extraction.readNow(index: Int): EntryResult = runBlocking {
        withTimeout(5_000) { read(entries(pages)[index], ArchiveSession.MAX_ENTRY_BYTES) }
    }

    private fun assertData(expected: ByteArray, result: EntryResult) {
        assertTrue("reads the data: $result", result is EntryResult.Data)
        assertArrayEquals(expected, (result as EntryResult.Data).bytes)
    }

    @Test
    fun `an entry larger than the cache limit is still handed to the reader`() {
        val extraction = extraction(temp.newFolder("book"), maxCacheBytes = 1000)

        assertData(pages[0].second, extraction.readNow(0))
        assertData(pages[1].second, extraction.readNow(1))
        assertData(pages[0].second, extraction.readNow(0))
        extraction.close()
    }

    @Test
    fun `a cache directory removed while the book is open is extracted again`() {
        val dir = temp.newFolder("book")
        val extraction = extraction(dir)
        assertData(pages[2].second, extraction.readNow(2))

        assertTrue(dir.deleteRecursively())

        assertData(pages[2].second, extraction.readNow(2))
        assertData(pages[0].second, extraction.readNow(0))
        extraction.close()
    }

    @Test
    fun `closing the session during an extraction leaves no unfinished file`() {
        val dir = temp.newFolder("book")
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val source = MemorySource(pages.flatMap { it.second.toList() }.toByteArray()) {
            reading.countDown()
            resume.await()
        }
        val extraction = extraction(dir, source)
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".part") })

        extraction.close()
        resume.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(emptyList<String>(), dir.listFiles()!!.map { it.name })
    }
}
