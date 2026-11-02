package io.github.yuubiz.hirari.tools.corpus

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextCorpusTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `two runs write the same files and a manifest line for each`() {
        val first = temp.newFolder().also { TextCorpus.main(arrayOf(it.path)) }
        val second = temp.newFolder().also { TextCorpus.main(arrayOf(it.path)) }
        val cases = textCases()
        for (path in cases.keys + TEXT_MANIFEST) assertContentEquals(first.resolve(path).readBytes(), second.resolve(path).readBytes(), path)
        assertEquals(cases.keys.toList(), first.resolve(TEXT_MANIFEST).readLines().map { it.substringBefore('\t') })
    }

    // The JDK's strict decoder is independent of the reader, so it checks that the clean cases hold their text.
    @Test
    fun `every clean case decodes strictly to its text`() {
        for ((path, case) in textCases().filterKeys { it != "text/cp932-invalid.txt" }) {
            val bom = when (case.charset.name()) {
                "UTF-16LE", "UTF-16BE" -> 2
                else -> if (path.contains("bom")) 3 else 0
            }
            val decoded = case.charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(case.bytes, bom, case.bytes.size - bom))
                .toString()
            assertEquals(case.text, decoded.replace("\r\n", "\n"), path)
        }
    }

    @Test
    fun `the invalid case has bad sequences where its text has replacements`() {
        val case = textCases().getValue("text/cp932-invalid.txt")
        assertEquals(2, case.text.count { it == '�' })
        assertEquals(case.text, String(case.bytes, case.charset))
    }

    @Test
    fun `the large case is at least 10 MB`() {
        assertTrue(textCases().getValue("text/cp932-10mb.txt").bytes.size >= 10_000_000)
    }
}
