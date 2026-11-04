package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** How much an SMB source fetches for the reads it serves (docs/spec.md section 3.1). */
class SmbByteSourceTest {
    private val bytes = Random(5).nextBytes(6_000_000)
    private val connector = FakeConnector(mapOf("book.zip" to bytes))

    private fun TestScope.open(): SmbByteSource =
        SmbServer(connector, SmbLogin.Guest, backgroundScope, kotlinx.coroutines.flow.emptyFlow(), StandardTestDispatcher(testScheduler), 1.seconds)
            .open("book.zip")

    /** One read of up to [length] bytes at [offset], as libarchive's callback makes it. */
    private fun SmbByteSource.once(offset: Long, length: Int): ByteArray {
        val out = ByteBuffer.allocate(length)
        read(offset, out)
        return out.array().copyOf(out.position())
    }

    /** Reads [length] bytes at [offset] whatever it takes. */
    private fun SmbByteSource.whole(offset: Long, length: Int): ByteArray {
        val out = ByteBuffer.allocate(length)
        while (out.hasRemaining() && read(offset + out.position(), out) > 0) Unit
        return out.array().copyOf(out.position())
    }

    private val fetched: List<Pair<Long, Int>> get() = connector.current.requested.toList()

    @Test
    fun `a small read fetches 16 KB, and the reads inside it take no request`() = runTest {
        open().use { source ->
            assertArrayEquals(bytes.copyOfRange(100, 130), source.once(100, 30))
            for (offset in 0L until SMB_MIN_FETCH - 100 step 4096) source.once(offset + 100, 100)
            assertEquals(listOf(100L to SMB_MIN_FETCH), fetched)
        }
    }

    @Test
    fun `small reads that go on from each other fetch twice as much each time, up to a megabyte`() = runTest {
        open().use { source ->
            var offset = 0L
            val first = source.once(offset, 4096)
            offset += first.size
            while (offset < 4_500_000) offset += source.once(offset, 64 * 1024).size
            val lengths = fetched.map { it.second }
            assertEquals(listOf(16, 64, 128, 256, 512, 1024, 1024, 1024).map { it * 1024 }, lengths.take(8))
            assertEquals("each fetch starts where the one before ended", fetched.zipWithNext().all { (a, b) -> a.first + a.second == b.first }, true)
        }
    }

    @Test
    fun `a larger read fetches just what it asks for, in one request`() = runTest {
        open().use { source ->
            assertArrayEquals(bytes.copyOfRange(5000, 1_305_000), source.whole(5000, 1_300_000))
            assertArrayEquals(bytes.copyOfRange(1_305_000, 1_405_000), source.whole(1_305_000, 100_000))
            assertEquals(listOf(5000L to 1_300_000, 1_305_000L to 100_000), fetched)
        }
    }

    @Test
    fun `the head, the tail and the place being read stay cached while reads go back and forth`() = runTest {
        open().use { source ->
            source.once(0, 1024)
            source.whole(bytes.size - 65_633L, 65_633)
            source.whole(3_000_000, 1_200_000)
            val requests = fetched.size
            assertArrayEquals(bytes.copyOf(64), source.once(0, 64))
            assertArrayEquals(bytes.copyOfRange(bytes.size - 22, bytes.size), source.once(bytes.size - 22L, 22))
            assertArrayEquals(bytes.copyOfRange(3_500_000, 3_500_100), source.once(3_500_000, 100))
            assertEquals("no request for ranges already fetched", requests, fetched.size)
        }
    }

    @Test
    fun `a fetch stops at the end of the file`() = runTest {
        open().use { source ->
            assertArrayEquals(bytes.copyOfRange(bytes.size - 10, bytes.size), source.whole(bytes.size - 10L, 100))
            assertEquals(listOf(bytes.size - 10L to 10), fetched)
            assertEquals(-1, source.read(bytes.size.toLong(), ByteBuffer.allocate(1)))
        }
    }

    @Test
    fun `the traffic counts every request and the bytes it returned`() = runTest {
        open().use { source ->
            source.once(0, 10)
            source.whole(2_000_000, 300_000)
            assertEquals(SmbTraffic(requests = 2, bytes = SMB_MIN_FETCH + 300_000L), source.traffic)
        }
    }
}
