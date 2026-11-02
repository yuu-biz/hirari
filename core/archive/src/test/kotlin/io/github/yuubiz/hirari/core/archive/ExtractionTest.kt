package io.github.yuubiz.hirari.core.archive

import kotlinx.coroutines.CoroutineDispatcher
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class ExtractionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private val pages = listOf("001.jpg" to letters(3000, 1), "002.jpg" to letters(500, 2), "003.jpg" to letters(800, 3))
    private val tallLast = listOf("001.jpg" to letters(500, 1), "002.jpg" to letters(400, 2), "003.jpg" to letters(3000, 3))

    @After
    fun stop() {
        executor.shutdownNow()
    }

    private fun entries(pages: List<Pair<String, ByteArray>>) = pages.mapIndexed { i, (name, data) ->
        ArchiveEntry(i, name.toByteArray(), data.size.toLong(), isDirectory = false, encrypted = false, compression = null)
    }

    private fun extraction(
        dir: File,
        pages: List<Pair<String, ByteArray>> = this.pages,
        source: MemorySource = MemorySource(pages.flatMap { it.second.toList() }.toByteArray()),
        maxCacheBytes: Long = 1L shl 30,
    ) = Extraction(FakeArchive(source, pages), dispatcher, entries(pages), dir, maxCacheBytes = maxCacheBytes)

    private fun Extraction.readNow(index: Int, pages: List<Pair<String, ByteArray>> = this@ExtractionTest.pages): EntryResult = runBlocking {
        withTimeout(5_000) { read(entries(pages)[index], ArchiveSession.MAX_ENTRY_BYTES) }
    }

    private fun cachedBytes(dir: File) = dir.listFiles()!!.sumOf { it.length() }

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
    fun `the cache returns under the limit once a page larger than it is handed over`() {
        val dir = temp.newFolder("book")
        val extraction = extraction(dir, tallLast, maxCacheBytes = 1000)

        assertData(tallLast[2].second, extraction.readNow(2, tallLast))
        executor.submit {}.get(5, TimeUnit.SECONDS)

        assertTrue("cached ${cachedBytes(dir)} bytes", cachedBytes(dir) <= 1000)
        extraction.close()
    }

    @Test
    fun `closing after a page larger than the limit leaves the cache under the limit`() {
        val dir = temp.newFolder("book")
        val extraction = extraction(dir, tallLast, maxCacheBytes = 1000)

        assertData(tallLast[2].second, extraction.readNow(2, tallLast))
        extraction.close()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        assertTrue("cached ${cachedBytes(dir)} bytes", cachedBytes(dir) <= 1000)
    }

    @Test
    fun `a session sharing the directory spares the file a read of the other session is about to open`() {
        val dir = temp.newFolder("book")
        val io = HeldDispatcher()
        val started = CountDownLatch(1)
        val readerArchive = FakeArchive(MemorySource(pages.flatMap { it.second.toList() }.toByteArray()) { started.await() }, pages)
        val reader = Extraction(readerArchive, dispatcher, entries(pages), dir, maxCacheBytes = 1000, io = io)
        val read = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { reader.read(entries(pages)[0], ArchiveSession.MAX_ENTRY_BYTES) }
        }
        started.countDown()
        val opening = io.next()
        executor.submit {}.get(5, TimeUnit.SECONDS)

        val thumbnailExecutor = Executors.newSingleThreadExecutor()
        val thumbnail = Extraction(
            FakeArchive(MemorySource(pages.flatMap { it.second.toList() }.toByteArray()), pages),
            thumbnailExecutor.asCoroutineDispatcher(),
            entries(pages),
            dir,
            maxCacheBytes = 1000,
        )
        assertData(pages[2].second, thumbnail.readNow(2))
        thumbnailExecutor.submit {}.get(5, TimeUnit.SECONDS)

        opening.run()
        while (!read.isCompleted) io.poll()?.run()
        assertData(pages[0].second, runBlocking { read.await() })
        assertEquals("opens of the reader's archive", 1, readerArchive.opens.get())

        reader.close()
        thumbnail.close()
        thumbnailExecutor.shutdown()
        assertTrue(thumbnailExecutor.awaitTermination(5, TimeUnit.SECONDS))
        val later = extraction(dir, maxCacheBytes = 1000)
        assertData(pages[2].second, later.readNow(2))
        executor.submit {}.get(5, TimeUnit.SECONDS)
        assertTrue("cached ${cachedBytes(dir)} bytes", cachedBytes(dir) <= 1000)
        later.close()
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
        val extraction = extraction(dir, source = source)
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".part") })

        extraction.close()
        resume.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(emptyList<String>(), dir.listFiles()!!.map { it.name })
    }

    /** Holds each block dispatched to it until the test runs it. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val held = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) = held.put(block)

        fun next(): Runnable = held.poll(5, TimeUnit.SECONDS) ?: error("nothing was dispatched")

        fun poll(): Runnable? = held.poll(100, TimeUnit.MILLISECONDS)
    }
}
