package io.github.yuubiz.hirari.core.archive

import kotlinx.coroutines.Job
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import kotlin.random.Random

/** What the central directory listing gives for layouts the corpus does not hold (docs/spec.md sections 3.3, 3.4). */
class CentralZipListTest {
    private fun list(bytes: ByteArray): List<ZipRecord>? = CentralZip.list(MemorySource(bytes), Job())

    private fun names(bytes: ByteArray): List<String> = list(bytes)!!.map { String(it.name, Charsets.UTF_8) }

    @Test
    fun `entries come in local header order whatever the order of the central directory`() {
        val zip = testZip(listOf(TestEntry("a.jpg"), TestEntry("b.jpg"), TestEntry("c.jpg")), centralOrder = listOf(2, 0, 1))
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), names(zip))
    }

    @Test
    fun `two records of one local header list once, as the first record names it`() {
        val zip = testZip(listOf(TestEntry("a.jpg"), TestEntry("b.jpg")), offsetOf = { 0 })
        assertEquals(listOf("a.jpg"), names(zip))
    }

    @Test
    fun `a Unicode Path field names the entry when it was written for the main name`() {
        val cp932 = "表紙.jpg".toByteArray(Charset.forName("windows-31j"))
        val current = TestEntry(cp932, centralExtra = unicodePath(cp932, "表紙.jpg"))
        val outdated = TestEntry(cp932, centralExtra = unicodePath("old.jpg".toByteArray(), "古い.jpg"))
        assertEquals(listOf("表紙.jpg"), names(testZip(listOf(current))))
        assertArrayEquals(cp932, list(testZip(listOf(outdated)))!!.single().name)
    }

    @Test
    fun `a flagged name is NFC and one that is not UTF-8 has no name`() {
        val nfd = TestEntry("が.jpg".toByteArray(), flags = 1 shl 11)
        val broken = TestEntry(byteArrayOf(0x81.toByte(), 'a'.code.toByte()), flags = 1 shl 11)
        assertEquals(listOf("が.jpg"), names(testZip(listOf(nfd))))
        assertEquals(0, list(testZip(listOf(broken)))!!.single().name.size)
    }

    @Test
    fun `backslashes separate folders only in a UTF-8 name without slashes`() {
        val cp932 = "表\\1.jpg".toByteArray(Charset.forName("windows-31j"))
        val zip = testZip(listOf(TestEntry("ch1\\001.jpg"), TestEntry("ch2/a\\b.jpg"), TestEntry(cp932)))
        val records = list(zip)!!
        assertEquals(listOf("ch1/001.jpg", "ch2/a\\b.jpg"), records.take(2).map { String(it.name, Charsets.UTF_8) })
        assertArrayEquals(cp932, records[2].name)
    }

    @Test
    fun `a name ends at its first NUL`() {
        assertEquals(listOf("a.jpg"), names(testZip(listOf(TestEntry("a.jpg\u0000junk")))))
    }

    @Test
    fun `directories come from the MS-DOS attribute, the Unix mode or a trailing slash, and end in a slash`() {
        val zip = testZip(
            listOf(
                TestEntry("dos".toByteArray(), external = 0x10),
                TestEntry("unix".toByteArray(), system = 3, external = 0x41ed_0000L),
                TestEntry("slash/"),
                TestEntry("file.jpg".toByteArray(), system = 3, external = 0x81a4_0000L),
            ),
        )
        val records = list(zip)!!
        assertEquals(listOf("dos/", "unix/", "slash/", "file.jpg"), records.map { String(it.name, Charsets.UTF_8) })
        assertEquals(listOf(true, true, true, false), records.map { it.entry(0).isDirectory })
    }

    @Test
    fun `a symbolic link lists as an empty file`() {
        val link = TestEntry("link.jpg".toByteArray(), "target.jpg".toByteArray(), system = 3, external = 0xa1ff_0000L)
        val entry = list(testZip(listOf(link)))!!.single().entry(0)
        assertEquals(0L, entry.declaredSize)
        assertEquals(false, entry.isDirectory)
    }

    @Test
    fun `methods and encryption come from the records`() {
        val zip = testZip(listOf(TestEntry("a.jpg".toByteArray(), method = 12), TestEntry("b.jpg".toByteArray(), flags = 1, method = 99)))
        val entries = list(zip)!!.mapIndexed { i, record -> record.entry(i) }
        assertEquals(listOf("bzip", "aes"), entries.map { it.compression })
        assertEquals(listOf(false, true), entries.map { it.encrypted })
    }

    @Test
    fun `a directory that is not whole and where its end record says is left to libarchive`() {
        val zip = testZip(listOf(TestEntry("a.jpg", ByteArray(100)), TestEntry("b.jpg", ByteArray(100))))
        assertTrue(list(zip)!!.size == 2)
        assertNull("data after the end record", list(zip + byteArrayOf(0)))
        assertNull("data before the archive", list(ByteArray(10) + zip))
        assertNull("a cut central directory", list(zip.copyOf(zip.size - 30)))
        val badSignature = zip.copyOf().also { it[zip.size - 22 - (46 + 5)] = 0 }
        assertNull("a broken record", list(badSignature))
        val fewer = zip.copyOf().also {
            it[zip.size - 22 + 8] = 1
            it[zip.size - 22 + 10] = 1
        }
        assertNull("a count that is not the records'", list(fewer))
        assertNull("an extra field that overruns", list(testZip(listOf(TestEntry("a".toByteArray(), centralExtra = extraField(0x1234, ByteArray(4)).copyOf(6))))))
    }

    @Test
    fun `a comment of any length is found`() {
        val zip = testZip(listOf(TestEntry("a.jpg", ByteArray(10))), comment = ByteArray(0xffff) { 'c'.code.toByte() })
        assertEquals(listOf("a.jpg"), names(zip))
    }

    @Test
    fun `damaged ZIPs list or go to libarchive and never throw`() {
        val random = Random(3)
        val base = testZip(
            listOf(
                TestEntry("001.jpg", ByteArray(3000) { it.toByte() }, method = 8),
                TestEntry("dir/".toByteArray(), external = 0x10),
                TestEntry("002.jpg", ByteArray(500) { (it * 7).toByte() }),
            ),
        )
        repeat(20_000) {
            val bytes = base.copyOf()
            repeat(1 + random.nextInt(4)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            val cut = if (random.nextInt(5) == 0) bytes.copyOf(random.nextInt(bytes.size)) else bytes
            list(cut)
        }
    }
}
