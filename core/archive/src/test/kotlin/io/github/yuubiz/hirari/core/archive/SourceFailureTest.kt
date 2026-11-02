package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** What a session reports when its source fails, and how it recovers once the source reads again (6.5). */
class SourceFailureTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val pages = listOf("001.jpg" to letters(3000, 1), "002.jpg" to letters(2500, 2), "003.jpg" to letters(800, 3))

    /** A source that throws what [failure] gives while it is set, as an SMB source does while it reconnects. */
    private class FlakySource(bytes: ByteArray) {
        @Volatile
        var failure: (() -> Exception)? = null

        val source = MemorySource(bytes) { failure?.let { throw it() } }
    }

    private fun flaky() = FlakySource(pages.flatMap { it.second.toList() }.toByteArray())

    private fun open(flaky: FlakySource, format: ArchiveFormat, extractDir: File?): ArchiveSession = runBlocking {
        val opened = ArchiveSession.open(flaky.source, extractDir) { FakeArchive(it, pages, format) }
        (opened as OpenResult.Opened).session
    }

    private fun ArchiveSession.readNow(index: Int): EntryResult = runBlocking { withTimeout(5_000) { read(index) } }

    private fun assertRecovers(format: ArchiveFormat, extractDir: File?, thrown: () -> Exception, reported: ArchiveFailure) {
        val flaky = flaky().apply { failure = thrown }
        val session = open(flaky, format, extractDir)

        assertEquals(EntryResult.Failed(reported), session.readNow(1))
        assertEquals(EntryResult.Failed(reported), session.readNow(1))

        flaky.failure = null
        val result = session.readNow(1)
        assertTrue("reads after the source recovers: $result", result is EntryResult.Data)
        assertArrayEquals(pages[1].second, (result as EntryResult.Data).bytes)
        session.close()
    }

    @Test
    fun `a lost connection fails the read as ConnectionLost and the next read after reconnecting succeeds`() {
        assertRecovers(ArchiveFormat.Zip, null, { ConnectionLostException("dropped") }, ArchiveFailure.ConnectionLost)
    }

    @Test
    fun `a solid book read through the extraction cache recovers from a lost connection too`() {
        assertRecovers(ArchiveFormat.SevenZip, temp.newFolder("book"), { ConnectionLostException("dropped") }, ArchiveFailure.ConnectionLost)
    }

    @Test
    fun `any other source exception is a ReadError, which the next read retries`() {
        assertRecovers(ArchiveFormat.Zip, null, { IOException("disk") }, ArchiveFailure.ReadError)
        assertRecovers(ArchiveFormat.SevenZip, temp.newFolder("other"), { IOException("disk") }, ArchiveFailure.ReadError)
    }
}
