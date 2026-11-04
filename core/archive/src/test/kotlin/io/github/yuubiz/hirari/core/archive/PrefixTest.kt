package io.github.yuubiz.hirari.core.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PrefixTest {
    @Test
    fun aNonSolidNativeReadStopsAtThePrefixAndCanStillReadTheWholeEntry() = runBlocking {
        val payload = letters(128 * 1024, 1)
        var reads = 0
        val source = MemorySource(payload) { reads++ }
        val session = (ArchiveSession.open(source, null) {
            FakeArchive(it, listOf("001" to payload), ArchiveFormat.Tar)
        } as OpenResult.Opened).session
        try {
            reads = 0
            val prefix = session.readPrefix(0, 32) as EntryResult.Data
            assertArrayEquals(payload.copyOf(32), prefix.bytes)
            assertEquals(1, reads) // One libarchive block, rather than every block of the entry.
            assertArrayEquals(payload, (session.read(0) as EntryResult.Data).bytes)
        } finally {
            session.close()
        }
    }

    @Test
    fun aNativePrefixFailureIsNotASuccessfulEmptyRead() = runBlocking {
        val payload = letters(4096, 1)
        var fail = false
        val source = MemorySource(payload) { if (fail) throw java.io.IOException("read failed") }
        val session = (ArchiveSession.open(source, null) {
            FakeArchive(it, listOf("001" to payload), ArchiveFormat.Tar)
        } as OpenResult.Opened).session
        try {
            fail = true
            assertEquals(EntryResult.Failed(ArchiveFailure.ReadError), session.readPrefix(0, 32))
        } finally {
            session.close()
        }
    }
}
