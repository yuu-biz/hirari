package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.model.BookPassword
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Exercises the session policy with real stored ZipCrypto bytes. JNI is covered by ZipPasswordTest. */
class ZipPasswordCorpusTest {
    private val zip = File(System.getProperty("hirari.corpus"), "zip/zipcrypto.zip").readBytes()
    private val records = CentralZip.list(MemorySource(zip), Job())!!
    private val notes = records.indexOfFirst { it.name.decodeToString() == "notes.txt" }

    /** Test-only decoder of PKWARE APPNOTE 6.1, used to verify the one-byte false positive. */
    private fun decrypt(record: ZipRecord, password: String): ByteArray {
        var k0 = 0x12345678
        var k1 = 0x23456789
        var k2 = 0x34567890
        fun crc(state: Int, byte: Int): Int {
            var value = state xor (byte and 255)
            repeat(8) { value = (value ushr 1) xor (if (value and 1 != 0) 0xedb88320.toInt() else 0) }
            return value
        }
        fun update(byte: Int) {
            k0 = crc(k0, byte)
            k1 = (k1 + (k0 and 255)) * 134775813 + 1
            k2 = crc(k2, k1 ushr 24)
        }
        password.encodeToByteArray().forEach { update(it.toInt()) }
        val start = record.headerOffset.toInt() + record.expectedHeaderLength
        return ByteArray(record.compressedSize.toInt()) { i ->
            val t = (k2 and 0xffff) or 2
            val plain = ((zip[start + i].toInt() and 255) xor ((t * (t xor 1)) ushr 8)) and 255
            update(plain)
            plain.toByte()
        }
    }

    private inner class StoredHandle : ArchiveHandle {
        private var pending = ""
        private var phrase = ""
        private var at = -1
        var headerPassed = false
        private var failure = ArchiveFailure.Corrupt
        override val header: Header? = null
        override fun begin(job: Job) = Unit
        override fun setPassword(password: CharArray?) { pending = password?.concatToString() ?: "" }
        override fun open(jump: Jump?): Int { at = -1; phrase = pending; return Status.OK }
        override fun nextHeader(): Int { at++; return Status.OK }
        override fun readData(sink: DataSink): Int {
            assertEquals(notes, at)
            val record = records[at]
            assertEquals("this fixture must use stored data", 0, record.method)
            assertEquals("this fixture checks the CRC header byte", 0, record.flags and 8)
            val decoded = decrypt(record, phrase)
            headerPassed = (decoded[11].toInt() and 255) == (record.crc ushr 24)
            if (!headerPassed) { failure = ArchiveFailure.PasswordRequired; return Status.PASSWORD_REQUIRED }
            val data = decoded.copyOfRange(12, decoded.size)
            if (CRC32().apply { update(data) }.value.toInt() != record.crc) {
                failure = ArchiveFailure.Corrupt
                return Status.FAILED
            }
            sink.write(ByteBuffer.wrap(data), 0)
            return Status.OK
        }
        override fun failure() = failure
        override fun close() = Unit
    }

    @Test fun `wrong-131 passes the corpus header but CRC failure still asks again`() = runBlocking {
        val wrong = decrypt(records[notes], "wrong-131")
        assertEquals(records[notes].crc ushr 24, wrong[11].toInt() and 255)
        assertNotEquals(records[notes].crc, CRC32().apply { update(wrong, 12, wrong.size - 12) }.value.toInt())
        val handle = StoredHandle()
        val wrongs = mutableListOf<Boolean>()
        var accepted = 0
        val password = object : BookPassword {
            override suspend fun initial() = "wrong-131".toCharArray()
            override suspend fun retry(wrong: Boolean): CharArray {
                assertTrue(handle.headerPassed)
                wrongs += wrong
                return "hirari".toCharArray()
            }
            override suspend fun accepted() { accepted++ }
        }
        (ArchiveSession.open(MemorySource(zip), null, { handle }, password) as OpenResult.Opened).session.use {
            assertEquals("password protected text", (it.read(notes) as EntryResult.Data).bytes.decodeToString())
            assertEquals(listOf(true), wrongs)
            assertEquals(1, accepted)
        }
    }

    @Test fun `a silent cover returns password required after a header false positive`() = runBlocking {
        var rejected = false
        val password = object : BookPassword {
            override suspend fun initial() = "wrong-131".toCharArray()
            override suspend fun retry(wrong: Boolean): CharArray? { rejected = wrong; return null }
            override suspend fun accepted() { fail("an unverified password was accepted") }
        }
        (ArchiveSession.open(MemorySource(zip), null, { StoredHandle() }, password) as OpenResult.Opened).session.use {
            assertEquals(EntryResult.Failed(ArchiveFailure.PasswordRequired), it.read(notes))
            assertTrue(rejected)
        }
    }
}
