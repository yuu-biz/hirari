package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/** Reading the entries of a ZIP listed from its central directory (docs/spec.md section 3.3). */
class CentralZipReadTest {
    /** [bytes] with a record of every read: where it started and how much it asked for. */
    private class Recorded(bytes: ByteArray) : ByteSource {
        private val memory = MemorySource(bytes)
        val reads = mutableListOf<LongRange>()

        @Volatile
        var failure: Exception? = null

        @Volatile
        var gate: CountDownLatch? = null
        val waiting = CountDownLatch(1)
        val count = AtomicInteger()

        override val size: Long = memory.size

        override fun read(offset: Long, dst: ByteBuffer): Int {
            failure?.let { throw it }
            gate?.let {
                waiting.countDown()
                it.await(5, TimeUnit.SECONDS)
            }
            count.incrementAndGet()
            synchronized(reads) { reads += offset until offset + dst.remaining() }
            return memory.read(offset, dst)
        }

        override fun close() = Unit
    }

    private class LibarchiveCalled : RuntimeException()

    private fun open(source: ByteSource): ArchiveSession = runBlocking {
        (ArchiveSession.open(source, null) { throw LibarchiveCalled() } as OpenResult.Opened).session
    }

    private fun ArchiveSession.readNow(index: Int, maxBytes: Int = ArchiveSession.MAX_ENTRY_BYTES): EntryResult =
        runBlocking { withTimeout(5_000) { read(index, maxBytes) } }

    private fun pages(count: Int, size: Int) = List(count) { i -> ByteArray(size) { (it * 31 + i * 7 + it / 253).toByte() } }

    @Test
    fun `an entry is read from its local header on, without reading other entries or opening libarchive`() {
        val pages = pages(20, 300_000)
        val zip = testZip(pages.mapIndexed { i, data -> TestEntry("%03d.jpg".format(i), data, method = if (i % 2 == 0) 0 else 8) })
        val source = Recorded(zip)
        open(source).use { session ->
            val listing = source.reads.toList()
            assertEquals("the tail holds the whole central directory", 2, listing.size)
            for (index in listOf(13, 2, 19, 0, 13)) {
                source.reads.clear()
                assertArrayEquals(pages[index], (session.readNow(index) as EntryResult.Data).bytes)
                val from = zip.indexOf(index)
                assertEquals("one read for entry $index", 1, source.reads.size)
                assertEquals(from, source.reads.single().first)
            }
        }
    }

    /** Where the local header of the entry at [index] starts in a ZIP laid out by [testZip]. */
    private fun ByteArray.indexOf(index: Int): Long {
        var at = 0
        repeat(index) {
            val compressed = u32le(at + 18)
            at += 30 + u16le(at + 26) + u16le(at + 28) + compressed.toInt()
        }
        return at.toLong()
    }

    private fun ByteArray.u16le(at: Int) = (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.u32le(at: Int) = u16le(at).toLong() or (u16le(at + 2).toLong() shl 16)

    @Test
    fun `a large entry is read in reads of a megabyte, the last one taking the rest`() {
        val page = Random(1).nextBytes(2_300_000)
        val source = Recorded(testZip(listOf(TestEntry("big.jpg", page))))
        open(source).use { session ->
            source.reads.clear()
            assertArrayEquals(page, (session.readNow(0) as EntryResult.Data).bytes)
            assertEquals(listOf(1 shl 20, 2_300_000 + 37 - (1 shl 20)), source.reads.map { (it.last - it.first + 1).toInt() })
        }
    }

    @Test
    fun `a local header longer than the record expects is skipped`() {
        val data = ByteArray(5000) { it.toByte() }
        val zip = testZip(listOf(TestEntry("a.jpg".toByteArray(), data, method = 8, localExtra = extraField(0x5455, ByteArray(9)))))
        open(MemorySource(zip)).use { session -> assertArrayEquals(data, (session.readNow(0) as EntryResult.Data).bytes) }
    }

    @Test
    fun `broken entries fail as libarchive fails them`() {
        val data = ByteArray(4000) { (it % 97).toByte() }
        val zip = testZip(
            listOf(
                TestEntry("crc.jpg".toByteArray(), data, method = 8, crc = 1234),
                TestEntry("size.jpg".toByteArray(), data, size = 3999),
                TestEntry("stream.jpg".toByteArray(), method = 8, stored = ByteArray(64) { -1 }, size = 4000),
                TestEntry("short.jpg".toByteArray(), method = 8, stored = deflated(data).copyOf(100), size = 4000, crc = crcOf(data)),
                TestEntry("long.jpg".toByteArray(), method = 8, stored = deflated(data) + ByteArray(10), size = 4000, crc = crcOf(data)),
                TestEntry("ok.jpg", data),
            ),
        )
        open(MemorySource(zip)).use { session ->
            val results = (0 until 6).map { session.readNow(it) }
            assertEquals(List(5) { EntryResult.Failed(ArchiveFailure.Corrupt) }, results.take(5))
            assertArrayEquals(data, (results[5] as EntryResult.Data).bytes)
        }
        val missingHeader = zip.copyOf().also { it[zip.indexOf(5).toInt()] = 0 }
        open(MemorySource(missingHeader)).use { assertEquals(EntryResult.Failed(ArchiveFailure.Corrupt), it.readNow(5)) }
    }

    @Test
    fun `an entry whose data runs past the end of the file is truncated`() {
        val zip = testZip(listOf(TestEntry("a.jpg", ByteArray(4000) { it.toByte() })))
        val record = CentralZip.list(MemorySource(zip), Job())!!.single()
        // The file is cut after listing, as a file that shrinks while open would be.
        assertEquals(EntryResult.Failed(ArchiveFailure.Truncated), CentralZip.read(MemorySource(zip.copyOf(1000)), record, 1 shl 20, Job()))
    }

    @Test
    fun `data that passes the limit stops with TooLarge though the record declares less`() {
        val data = ByteArray(300_000)
        val zip = testZip(listOf(TestEntry("a.jpg".toByteArray(), data, method = 8, size = 100)))
        open(MemorySource(zip)).use { session ->
            val result = session.readNow(0, maxBytes = 100_000)
            assertTrue("$result", result is EntryResult.TooLarge && result.bytes > 100_000)
        }
    }

    @Test
    fun `a failing source fails the read as the source failed, and the next read succeeds`() {
        val data = ByteArray(3000) { it.toByte() }
        val source = Recorded(testZip(listOf(TestEntry("a.jpg", data), TestEntry("b.jpg", data, method = 8))))
        open(source).use { session ->
            source.failure = ConnectionLostException("dropped")
            assertEquals(EntryResult.Failed(ArchiveFailure.ConnectionLost), session.readNow(1))
            source.failure = IOException("disk")
            assertEquals(EntryResult.Failed(ArchiveFailure.ReadError), session.readNow(0))
            source.failure = null
            assertArrayEquals(data, (session.readNow(1) as EntryResult.Data).bytes)
        }
    }

    @Test
    fun `a source that fails while listing fails the open the same way`() {
        val zip = testZip(listOf(TestEntry("a.jpg", ByteArray(100_000))))
        val source = MemorySource(zip) { offset -> if (offset > 0) throw ConnectionLostException("dropped") }
        assertEquals(OpenResult.Failed(ArchiveFailure.ConnectionLost), runBlocking { ArchiveSession.open(source, null) { throw LibarchiveCalled() } })
    }

    @Test
    fun `cancelling a read stops after the read in progress and leaves the session usable`() = runBlocking {
        val page = Random(2).nextBytes(3_000_000)
        val source = Recorded(testZip(listOf(TestEntry("a.jpg", page))))
        open(source).use { session ->
            val gate = CountDownLatch(1)
            source.gate = gate
            val read = async(Dispatchers.Default) { session.read(0) }
            assertTrue(source.waiting.await(5, TimeUnit.SECONDS))
            read.cancel()
            source.gate = null
            val before = source.count.get()
            gate.countDown()
            try {
                read.await()
                fail("the cancelled read returned")
            } catch (_: CancellationException) {
            }
            assertEquals("reads after the held one", before + 1, source.count.get())
            assertArrayEquals(page, (session.read(0) as EntryResult.Data).bytes)
        }
    }

    @Test
    fun `PPMd entries are left to libarchive`() {
        val zip = testZip(listOf(TestEntry("a.jpg".toByteArray(), ByteArray(10), method = 98), TestEntry("b.jpg", ByteArray(10))))
        open(MemorySource(zip)).use { session ->
            assertEquals(10, (session.readNow(1) as EntryResult.Data).bytes.size)
            try {
                session.readNow(0)
                fail("read a PPMd entry without libarchive")
            } catch (_: LibarchiveCalled) {
            }
        }
    }

    @Test
    fun `damaged entries read or fail and never throw`() {
        val random = Random(4)
        val base = testZip(
            listOf(
                TestEntry("001.jpg", ByteArray(30_000) { (it / 7).toByte() }, method = 8),
                TestEntry("002.jpg", ByteArray(500) { (it * 7).toByte() }),
                TestEntry("003.jpg", ByteArray(9_000) { (it % 13).toByte() }, method = 8),
            ),
        )
        repeat(5_000) {
            val bytes = base.copyOf()
            repeat(1 + random.nextInt(6)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            val records = CentralZip.list(MemorySource(bytes), Job()) ?: return@repeat
            records.filter { CentralZip.reads(it) && !it.encrypted }.forEach {
                CentralZip.read(MemorySource(bytes), it, 1 shl 20, Job())
            }
        }
    }
}
