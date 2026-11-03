package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.source.smb.SmbPrefetchCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class StashTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val decoding = ImageDecoding(budgetBytes = 64L shl 20, parallelism = 1)
    private val cache by lazy { SmbPrefetchCache(temp.root.resolve("smb"), reserveBytes = 0) }

    /** Entries `10 + i` hold `i + 1` bytes of `i`, and fail while [failing]. */
    private class Server : PageBytes {
        val reads = AtomicInteger()
        var failing = false
        var outOfMemory = false
        var lost = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun read(page: PageEntry): PageResult<ByteArray> {
            reads.incrementAndGet()
            gate?.await()
            if (failing) throw IOException("gone")
            if (outOfMemory) throw OutOfMemoryError("entry")
            if (lost) return connectionLost
            val i = page.entryIndex - 10
            return PageResult.Ready(ByteArray(i + 1) { i.toByte() })
        }

        override fun close() = Unit
    }

    private val pages = List(3) { PageEntry(it, 10 + it, byteArrayOf(it.toByte()), "$it.jpg", declaredSize = null, sniffed = null) }

    private val written = Instant.parse("2026-11-01T10:00:00Z")

    private fun book(server: PageBytes, name: String = "book", size: Long = 1000, modified: Instant = written) =
        OpenBook(BookContent.Images(StashedPages(server, DiskStash(cache, BookId(name), size, modified))), Container.Zip, pages, indexError = null, decoding)

    private fun RawResult.bytes(): List<Byte> = ByteArrayOutputStream().also { ((this as PageResult.Ready).image as RawPage.Encoded).writeTo(it) }.toByteArray().toList()

    @Test
    fun aPageReadFromTheServerIsReadFromDiskTheNextTimeTheBookOpens() = runBlocking {
        val first = Server()
        val opened = book(first)
        val stash = opened.stash!!
        assertFalse(stash.holds(2))
        assertEquals(listOf<Byte>(2, 2, 2), opened.raw(2).bytes())
        assertTrue(stash.holds(2))
        opened.close()

        val again = Server()
        val reopened = book(again)
        assertEquals(listOf<Byte>(2, 2, 2), reopened.raw(2).bytes())
        assertEquals(0, again.reads.get())
        assertFalse(book(again, name = "another book").stash!!.holds(2))
    }

    @Test
    fun anArchiveReplacedByOneOfTheSameSizeIsReadFromTheServerAgain() = runBlocking {
        book(Server()).raw(2)
        val replaced = Server()
        val opened = book(replaced, modified = written.plusSeconds(60))
        assertFalse(opened.stash!!.holds(2))
        opened.raw(2)
        assertEquals(1, replaced.reads.get())
        assertFalse(book(Server(), size = 1001).stash!!.holds(2))
        assertTrue(book(Server()).stash!!.holds(2))
    }

    @Test
    fun aPagePrefetchedOntoDiskIsNotReadFromTheServerAgain() = runBlocking {
        val server = Server()
        val opened = book(server)
        assertEquals(Fill.OnDisk, opened.stash!!.fill(1))
        assertEquals(Fill.OnDisk, opened.stash!!.fill(1))
        assertEquals(listOf<Byte>(1, 1), opened.raw(1).bytes())
        assertEquals(1, server.reads.get())
    }

    @Test
    fun aViewWaitingOnThePrefetchOfItsPageGetsItWithoutASecondRead() = runBlocking {
        val server = Server().apply { gate = CompletableDeferred() }
        val opened = book(server)
        val filling = async { opened.stash!!.fill(0) }
        while (server.reads.get() == 0) kotlinx.coroutines.yield()
        val reading = async { opened.raw(0) }
        server.gate!!.complete(Unit)
        assertEquals(Fill.OnDisk, filling.await())
        assertEquals(listOf<Byte>(0), reading.await().bytes())
        assertEquals(1, server.reads.get())
    }

    @Test
    fun aViewWaitingOnThePrefetchOfItsPageGetsItOnceEvenWhenTheDiskCannotKeepIt() = runBlocking {
        val full = SmbPrefetchCache(temp.root.resolve("full"), maxBytes = 1, reserveBytes = 0)
        val server = Server().apply { gate = CompletableDeferred() }
        val opened = OpenBook(BookContent.Images(StashedPages(server, DiskStash(full, BookId("book"), 1000, written))), Container.Zip, pages, indexError = null, decoding)
        val filling = async { opened.stash!!.fill(2) }
        while (server.reads.get() == 0) kotlinx.coroutines.yield()
        val reading = async { opened.raw(2) }
        server.gate!!.complete(Unit)
        assertEquals(Fill.NotKept, filling.await())
        assertEquals(listOf<Byte>(2, 2, 2), reading.await().bytes())
        assertEquals(1, server.reads.get())
    }

    @Test
    fun aViewWaitingOnAPrefetchThatIsCancelledReadsThePageItself() = runBlocking {
        val server = Server().apply { gate = CompletableDeferred() }
        val opened = book(server)
        val filling = async { opened.stash!!.fill(1) }
        while (server.reads.get() == 0) kotlinx.coroutines.yield()
        val reading = async { opened.raw(1) }
        kotlinx.coroutines.yield()
        filling.cancel()
        server.gate!!.complete(Unit)
        assertEquals(listOf<Byte>(1, 1), reading.await().bytes())
        assertTrue(opened.stash!!.holds(1))
    }

    @Test
    fun aPageThatFailsToReadIsNotKept() = runBlocking {
        val server = Server().apply { failing = true }
        val opened = book(server)
        assertEquals(Fill.NotKept, opened.stash!!.fill(0))
        assertEquals(readError, opened.raw(0))
        assertFalse(opened.stash!!.holds(0))
        assertEquals(0L, cache.sizeBytes)
    }

    @Test
    fun aPrefetchThatRunsOutOfMemorySaysSoInsteadOfThrowing() = runBlocking {
        val server = Server().apply { outOfMemory = true }
        val opened = book(server)
        assertEquals(Fill.OutOfMemory, opened.stash!!.fill(0))
        assertEquals(readError, opened.raw(0))
        assertFalse(opened.stash!!.holds(0))
    }

    @Test
    fun aPrefetchOnABrokenConnectionSaysSo() = runBlocking {
        val server = Server().apply { lost = true }
        val opened = book(server)
        assertEquals(Fill.ConnectionLost, opened.stash!!.fill(0))
        server.lost = false
        assertEquals(Fill.OnDisk, opened.stash!!.fill(0))
    }

    @Test
    fun aBookWithoutAStashHasNoDiskLayer() = runBlocking {
        val opened = (Books.openFolder(emptyList(), decoding) as BookResult.Opened).book
        assertNull(opened.stash)
    }
}
