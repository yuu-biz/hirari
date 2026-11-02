package io.github.yuubiz.hirari.core.archive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class EntryReadRulesTest {
    private fun entry(
        compression: String? = "deflation",
        declaredSize: Long? = 100,
        encrypted: Boolean = false,
    ) = ArchiveEntry(0, "a.jpg".toByteArray(), declaredSize, isDirectory = false, encrypted = encrypted, compression = compression)

    @Test
    fun `stored and deflated entries within the limit are read`() {
        assertNull(ArchiveSession.refusal(entry("uncompressed"), 1000))
        assertNull(ArchiveSession.refusal(entry("deflation"), 1000))
        assertNull(ArchiveSession.refusal(entry(compression = null, declaredSize = null), 1000))
    }

    @Test
    fun `encryption is reported before the method, and the method before the size`() {
        assertEquals(EntryResult.Encrypted, ArchiveSession.refusal(entry("aes", 5000, encrypted = true), 1000))
        assertEquals(EntryResult.UnsupportedCompression("bzip"), ArchiveSession.refusal(entry("bzip", 5000), 1000))
        assertEquals(EntryResult.TooLarge(5000), ArchiveSession.refusal(entry("deflation", 5000), 1000))
    }

    @Test
    fun `the method comes from the parenthesized part of the format name`() {
        assertEquals("deflation", ArchiveSession.compressionOf("ZIP 2.0 (deflation)"))
        assertEquals("ppmd-1", ArchiveSession.compressionOf("ZIP 6.3 (ppmd-1)"))
        assertNull(ArchiveSession.compressionOf("ZIP"))
    }

    @Test
    fun `entry bytes assemble the blocks at their offsets and grow past the initial capacity`() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val sink = EntryBytes(limit = 1_000_000)

        assertTrue(sink.write(ByteBuffer.wrap(data, 0, 150_000), 0))
        assertTrue(sink.write(ByteBuffer.wrap(data, 150_000, 50_000).slice(), 150_000))

        assertArrayEquals(data, sink.toByteArray())
        assertNull(sink.exceeded)
    }

    @Test
    fun `entry bytes stop at the block that would pass the limit and report where it would end`() {
        val sink = EntryBytes(limit = 100)

        assertTrue(sink.write(ByteBuffer.wrap(ByteArray(60)), 0))
        assertFalse(sink.write(ByteBuffer.wrap(ByteArray(60)), 60))

        assertEquals(120L, sink.exceeded)
    }
}
