package io.github.yuubiz.hirari.tools.corpus

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorpusTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun generate(): File = temp.newFolder().also { main(arrayOf(it.path)) }

    private fun contents(dir: File): Map<String, List<Byte>> =
        dir.walk().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes().toList() }

    private fun manifestLines(dir: File): List<List<String>> =
        dir.resolve(MANIFEST).readText().lines().filter { it.isNotEmpty() }.map { it.split('\t') }

    @Test
    fun `two runs write the same files byte for byte`() {
        val first = contents(generate())
        assertEquals(corpusCases().keys + pdfCases().keys + MANIFEST + PAGES_MANIFEST + PASSWORDS_MANIFEST, first.keys)
        assertEquals(first, contents(generate()))
    }

    @Test
    fun `the manifest names every corpus file once, in an archive line`() {
        val dir = generate()
        val archives = manifestLines(dir).filter { it[0] == "archive" }.map { it[1] }
        assertEquals(corpusCases().keys.toList(), archives)
    }

    // The JDK reader is independent of the generator, so it checks that the cases it can read are well formed and
    // that their data matches the expectations. It needs the end record, which truncation removes, it rejects a
    // name that has the UTF-8 flag but is not UTF-8, and it has no PPMd decoder.
    @Test
    fun `the JDK reads every fully readable zip with the expected data in order`() {
        val dir = generate()
        val readable = corpusCases().filter { (path, case) ->
            val expected = case.expected
            path.startsWith("zip/") && !path.startsWith("zip/truncated-") && path != "zip/names-invalid-flag.zip" && path != "zip/ppmd.zip" &&
                expected is Expected.Opens && expected.indexError == null &&
                expected.entries.all { it.read is ExpectedRead.Data || it.read == ExpectedRead.Directory }
        }
        assertTrue("zip/zip64.zip" in readable && "zip/data-descriptor.zip" in readable && "zip/long-comment.zip" in readable)
        for ((path, case) in readable) {
            val expected = (case.expected as Expected.Opens).entries.map { (it.read as? ExpectedRead.Data)?.bytes?.toList() ?: emptyList() }
            val actual = ZipFile(dir.resolve(path), Charsets.ISO_8859_1).use { zip ->
                zip.entries().toList().map { zip.getInputStream(it).use { stream -> stream.readBytes().toList() } }
            }
            assertEquals(expected, actual, path)
        }
    }

    @Test
    fun `a zip cut inside the second entry's data lists two entries, fails the second, and stops with truncation`() {
        val dir = generate()
        val lines = manifestLines(dir)
        val start = lines.indexOf(listOf("archive", "zip/truncated-second-data.zip", "opens", "truncated"))
        assertTrue(start >= 0)
        val entries = lines.drop(start + 1).takeWhile { it[0] == "entry" }
        assertEquals(listOf("001.jpg", "data"), entries[0].subList(1, 3))
        assertEquals(listOf("entry", "002.jpg", "fails", "truncated"), entries[1])
        assertEquals(2, entries.size)
    }

    @Test
    fun `the cp932 names are written as cp932 bytes with a 0x5C trail byte`() {
        val bytes = generate().resolve("zip/names-cp932.zip").readBytes()
        val name = "表紙.jpg".toByteArray(charset("windows-31j"))
        assertEquals(0x5c, name[1].toInt())
        assertTrue(bytes.toList().windowed(name.size).any { it == name.toList() })
    }

    @Test
    fun `letters never contain the first byte of a zip signature`() {
        val sample = letters(100_000, 1)
        assertTrue(sample.none { it == 'P'.code.toByte() })
        assertContentEquals(sample, letters(100_000, 1))
    }

    @Test
    fun `password fixtures document passwords and distinguish traditional from AES encryption`() {
        val dir = generate()
        val passwords = dir.resolve(PASSWORDS_MANIFEST).readText()
        assertTrue("zip/zipcrypto.zip\thirari" in passwords)
        assertTrue("zip/aes.zip\thirari" in passwords)
        val cases = corpusCases()
        val traditional = cases.getValue("zip/zipcrypto.zip").expected as Expected.Opens
        val aes = cases.getValue("zip/aes.zip").expected as Expected.Opens
        assertEquals(ExpectedRead.Fails(Failure.PasswordRequired), traditional.entries[1].read)
        assertEquals(ExpectedRead.Unsupported("encrypted"), aes.entries[1].read)
        val pages = dir.resolve(PAGES_MANIFEST).readText()
        assertTrue("book\tzip/zipcrypto.zip\topens\t-" in pages)
        assertTrue("book\tzip/aes.zip\topens\t-" in pages)
        assertTrue("page\t002.jpg\tfails\tpasswordrequired" in pages)
    }
}
