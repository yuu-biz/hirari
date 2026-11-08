package io.github.yuubiz.hirari.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/** The copy of a document a provider hands over as a pipe (docs/spec.md sections 3.1 and 4.4). */
class CopyTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A pipe that gives 64 KB at each read, [chunks] times, as a slow provider does. */
    private class Pipe(private val chunks: Int) : InputStream() {
        var reads = 0

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (reads == chunks) return -1
            reads++
            b.fill(reads.toByte(), off, off + len)
            return len
        }
    }

    @Test
    fun `the whole document is copied while it is wanted`() {
        val pipe = Pipe(chunks = 3)
        copied(pipe, temp.newFolder(), lastModifiedMs = 7, isActive = { true }).use { copy ->
            assertEquals(3L * 64 * 1024, copy.size)
            assertEquals(7L, copy.lastModifiedMs)
            val last = ByteBuffer.allocate(1)
            copy.read(copy.size - 1, last)
            assertEquals(3.toByte(), last.get(0))
        }
    }

    @Test
    fun `a copy no longer wanted stops before the next chunk and closes its file`() {
        val pipe = Pipe(chunks = 1000)
        val dir = temp.newFolder()
        try {
            copied(pipe, dir, lastModifiedMs = null, isActive = { pipe.reads < 3 })
            fail("the copy went on")
        } catch (_: CancellationException) {
        }
        assertEquals(3, pipe.reads)
        // Where an open file cannot be deleted, as on Windows, its name is still there until it is closed.
        assertTrue(dir.listFiles().orEmpty().all { it.delete() })
    }
}
