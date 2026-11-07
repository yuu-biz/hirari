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
    private fun source(ppmdFirst: Boolean = false): MemorySource {
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
                else if (ppmdFirst) zip[i + 10] = 98
            }
        }
        return MemorySource(zip)
    }

    private class Handle : ArchiveHandle {
        var opens = 0
        var at = -1
        var right = false
        var nextRight = false
        var dataFailure: ArchiveFailure? = null
        var prefix = false
        override var header: Header? = null
        override fun begin(job: Job) = Unit
        override fun setPassword(password: CharArray?) { nextRight = password?.contentEquals("right".toCharArray()) == true }
        override fun open(jump: Jump?): Int { opens++; at = -1; right = nextRight; return Status.OK }
        override fun nextHeader(): Int { at++; return Status.OK }
        override fun readData(sink: DataSink): Int {
            if (at != 0 && !right) return Status.PASSWORD_REQUIRED
            if (dataFailure != null) return Status.FAILED
            sink.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 0)
            return if (prefix) Status.STOPPED else Status.OK
        }
        override fun failure() = dataFailure ?: ArchiveFailure.PasswordRequired
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

    @Test fun `installing a stored password after a plain native read reopens before the encrypted read`() = runBlocking {
        val handle = Handle()
        var retried = false
        val password = object : BookPassword {
            override suspend fun initial() = "right".toCharArray()
            override suspend fun retry(wrong: Boolean): CharArray? { retried = true; return null }
            override suspend fun accepted() = Unit
        }
        (ArchiveSession.open(source(ppmdFirst = true), null, { handle }, password) as OpenResult.Opened).session.use {
            assertTrue(it.read(0) is EntryResult.Data)
            assertEquals(1, handle.opens)
            assertTrue(it.read(1) is EntryResult.Data)
            assertEquals(2, handle.opens)
            assertFalse("the stored password was actually correct", retried)
        }
    }

    @Test fun `unverified encrypted read failures ask again but verified failures remain page errors`() = runBlocking {
        for (failure in listOf(ArchiveFailure.Corrupt, ArchiveFailure.Truncated, ArchiveFailure.ReadError)) {
            val handle = Handle()
            val wrongs = mutableListOf<Boolean>()
            val password = object : BookPassword {
                override suspend fun initial() = "right".toCharArray()
                override suspend fun retry(wrong: Boolean): CharArray? { wrongs += wrong; return null }
                override suspend fun accepted() = Unit
            }
            (ArchiveSession.open(source(), null, { handle }, password) as OpenResult.Opened).session.use {
                handle.dataFailure = failure
                assertEquals(EntryResult.Failed(ArchiveFailure.PasswordRequired), it.read(1))
                assertEquals(listOf(true), wrongs)
                handle.dataFailure = null
                assertTrue(it.read(1) is EntryResult.Data)
                handle.dataFailure = failure
                assertEquals(EntryResult.Failed(failure), it.read(1))
                assertEquals(listOf(true), wrongs)
            }
        }
    }

    @Test fun `a prefix does not verify or accept the password`() = runBlocking {
        val handle = Handle().also { it.prefix = true }
        var accepted = 0
        val password = object : BookPassword {
            override suspend fun initial() = "right".toCharArray()
            override suspend fun retry(wrong: Boolean): CharArray? = null
            override suspend fun accepted() { accepted++ }
        }
        (ArchiveSession.open(source(), null, { handle }, password) as OpenResult.Opened).session.use {
            assertTrue(it.readPrefix(1, 2) is EntryResult.Data)
            assertEquals(0, accepted)
            handle.dataFailure = ArchiveFailure.Corrupt
            assertEquals(EntryResult.Failed(ArchiveFailure.PasswordRequired), it.read(1))
        }
    }
}
