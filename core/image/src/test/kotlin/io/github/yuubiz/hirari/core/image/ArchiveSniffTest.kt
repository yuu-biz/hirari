package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.archive.ArchiveEntry
import io.github.yuubiz.hirari.core.archive.ArchiveFailure
import io.github.yuubiz.hirari.core.archive.EntryResult
import io.github.yuubiz.hirari.core.model.ImageType
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveSniffTest {
    private val decoding = ImageDecoding(budgetBytes = 64L shl 20, parallelism = 1)
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())

    @Test
    fun unknownStoredAndDeflatedImagesReadOnlyAPrefixBeforeOpening() = runBlocking {
        for (method in listOf(ZipEntry.STORED, ZipEntry.DEFLATED)) {
            val payload = kotlin.random.Random(42).nextBytes(256 * 1024).also { jpeg.copyInto(it) }
            val source = zipSource("001.unknown", payload, method)
            val book = (Books.openArchive(source, decoding) as BookResult.Opened).book
            try {
                assertEquals(ImageType.Jpeg, book.pages.single().sniffed)
                // The session's initial format probe asks for 64 bytes; the local header needs only 30.
                assertTrue(source.reads.filter { it.first == 0L }.all { it.second <= 64 })
                val header = source.reads.indexOfLast { it.first == 0L }
                assertTrue(source.reads.drop(header + 1).all { it.second <= 32 })
            } finally {
                book.close()
            }
            assertTrue(source.closed.await(5, TimeUnit.SECONDS))
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun aSniffReadFailureFailsTheOpenAndClosesTheArchive() = runBlocking {
        for (failure in listOf(IOException("read failed"), ConnectionLostException("disconnected"))) {
            val source = zipSource("001", ByteArray(256 * 1024).also { jpeg.copyInto(it) })
            source.sniffFailure = failure
            assertEquals(BookResult.Failed(BookFailure.ReadError), Books.openArchive(source, decoding))
            assertTrue(source.closed.await(5, TimeUnit.SECONDS))
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun aSuccessfullyReadUnknownNonImageIsExcluded() = runBlocking {
        val source = zipSource("001.unknown", ByteArray(256 * 1024) { 'x'.code.toByte() })
        val book = (Books.openArchive(source, decoding) as BookResult.Opened).book
        try {
            assertTrue(book.pages.isEmpty())
        } finally {
            book.close()
        }
    }

    @Test
    fun aSolidArchiveExcludesUnknownEntriesWithoutReadingAnyData() = runBlocking {
        val names = listOf("001", "002.unknown", "003.jpg", "004.txt")
        val entries = names.mapIndexed { i, name -> entry(i, name) }
        val result = archivePages(entries, solid = true, { String(it.rawName) }) { _, _ ->
            error("a solid archive must not be sniffed")
        } as Books.Safely.Done<List<PageEntry>>
        assertEquals(listOf("003.jpg"), result.value.map { it.name })
    }

    @Test
    fun sniffFailuresKeepTheirReasonsAndUnreadableCandidatesAreNotDropped() = runBlocking {
        for ((failure, expected) in listOf(
            ArchiveFailure.ConnectionLost to BookFailure.ReadError,
            ArchiveFailure.ReadError to BookFailure.ReadError,
            ArchiveFailure.Truncated to BookFailure.Truncated,
            ArchiveFailure.Corrupt to BookFailure.Corrupt,
        )) {
            val result = archivePages(listOf(entry(0, "001")), false, { "001" }) { _, count ->
                assertEquals(32, count)
                EntryResult.Failed(failure)
            }
            assertEquals(expected, (result as Books.Safely.Failed).reason)
        }
        for (unreadable in listOf(EntryResult.Encrypted, EntryResult.UnsupportedCompression("unknown"), EntryResult.TooLarge(1L shl 30))) {
            val result = archivePages(listOf(entry(0, "001")), false, { "001" }) { _, _ -> unreadable }
            assertEquals(1, (result as Books.Safely.Done<List<PageEntry>>).value.size)
        }
    }

    private fun entry(index: Int, name: String) = ArchiveEntry(index, name.toByteArray(), 1000, false, false, null)

    private fun zipSource(name: String, payload: ByteArray, method: Int = ZipEntry.STORED): ZipSource {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val entry = ZipEntry(name).also {
                it.method = method
                it.size = payload.size.toLong()
                it.crc = CRC32().apply { update(payload) }.value
            }
            zip.putNextEntry(entry)
            zip.write(payload)
            zip.closeEntry()
        }
        return ZipSource(output.toByteArray())
    }

    private class ZipSource(private val bytes: ByteArray) : ByteSource {
        override val size = bytes.size.toLong()
        val reads = ArrayList<Pair<Long, Int>>()
        var closes = 0
        val closed = CountDownLatch(1)
        var sniffFailure: IOException? = null

        override fun read(offset: Long, dst: ByteBuffer): Int {
            reads += offset to dst.remaining()
            if (offset == 0L && dst.remaining() != 64) sniffFailure?.let { throw it }
            if (offset >= size) return -1
            val count = minOf(dst.remaining().toLong(), size - offset).toInt()
            dst.put(bytes, offset.toInt(), count)
            return count
        }

        override fun close() {
            closes++
            closed.countDown()
        }
    }
}
