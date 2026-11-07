package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.model.BookPassword
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchivePasswordTest {
    /** Real central directory metadata; data is supplied by the fake libarchive handle. */
    private fun source(): MemorySource {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use {
            for (name in listOf("001.jpg", "002.jpg")) {
                it.putNextEntry(ZipEntry(name)); it.write(byteArrayOf(1, 2, 3)); it.closeEntry()
            }
        }
        val zip = out.toByteArray()
        var central = 0
        for (i in 0 until zip.size - 46) {
            if (zip[i] == 0x50.toByte() && zip[i + 1] == 0x4b.toByte() && zip[i + 2] == 1.toByte() && zip[i + 3] == 2.toByte()) {
                if (central++ == 1) zip[i + 8] = (zip[i + 8].toInt() or 1).toByte()
            }
        }
        return MemorySource(zip)
    }

    private class Handle : ArchiveHandle {
        var opens = 0
        var at = -1
        var right = false
        override var header: Header? = null
        override fun begin(job: Job) = Unit
        override fun setPassword(password: CharArray?) { right = password?.contentEquals("right".toCharArray()) == true }
        override fun open(jump: Jump?): Int { opens++; at = -1; return Status.OK }
        override fun nextHeader(): Int { at++; return Status.OK }
        override fun readData(sink: DataSink): Int {
            if (!right) return Status.PASSWORD_REQUIRED
            sink.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 0)
            return Status.OK
        }
        override fun failure() = ArchiveFailure.PasswordRequired
        override fun close() = Unit
    }

    @Test fun `plain entries never ask and each password retry reopens before reading`() = runBlocking {
        val handle = Handle()
        val wrongs = mutableListOf<Boolean>()
        var accepted = 0
        val password = object : BookPassword {
            override suspend fun initial(): CharArray? = null
            override suspend fun retry(wrong: Boolean): CharArray {
                wrongs += wrong
                return (if (wrongs.size == 1) "wrong" else "right").toCharArray()
            }
            override suspend fun accepted() { accepted++ }
        }
        val session = (ArchiveSession.open(source(), null, { handle }, password) as OpenResult.Opened).session
        session.use {
            assertTrue(it.read(0) is EntryResult.Data)
            assertTrue(wrongs.isEmpty())
            assertEquals(0, handle.opens)
            assertArrayEquals(byteArrayOf(1, 2, 3), (it.read(1) as EntryResult.Data).bytes)
            assertEquals(listOf(false, true), wrongs)
            assertEquals(3, handle.opens)
            assertEquals(1, accepted)
        }
    }

    @Test fun `a protected entry without a prompt reports password required instead of unsupported`() = runBlocking {
        val session = (ArchiveSession.open(source(), null) { Handle() } as OpenResult.Opened).session
        session.use { assertEquals(EntryResult.Failed(ArchiveFailure.PasswordRequired), it.read(1)) }
    }
}
