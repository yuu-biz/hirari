package io.github.yuubiz.hirari.tools.corpus

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class CorpusTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun generate(): File = temp.newFolder().also { main(arrayOf(it.path)) }

    private fun contents(dir: File): Map<String, List<Byte>> =
        dir.walk().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes().toList() }

    @Test
    fun `two runs write the same files byte for byte`() {
        val first = contents(generate())
        assertEquals(setOf("zip/names.zip"), first.keys)
        assertEquals(first, contents(generate()))
    }

    // The JDK decodes a name as UTF-8 only when its entry has the UTF-8 flag, and otherwise with the
    // charset given here, so the non-ASCII name matches only if the flag is set.
    @Test
    fun `names zip lists its names in order in both headers, with the non-ASCII name flagged as UTF-8`() {
        val zip = generate().resolve("zip/names.zip")
        val expected = listOf("001.txt", "chapter/002.txt", "日本語.txt")

        val local = ZipInputStream(zip.inputStream(), Charsets.ISO_8859_1).use { stream ->
            generateSequence { stream.nextEntry }.map { entry -> entry.name.also { stream.readBytes() } }.toList()
        }
        val central = ZipFile(zip, Charsets.ISO_8859_1).use { file -> file.entries().toList().map { it.name } }

        assertEquals(expected, local)
        assertEquals(expected, central)
    }
}
