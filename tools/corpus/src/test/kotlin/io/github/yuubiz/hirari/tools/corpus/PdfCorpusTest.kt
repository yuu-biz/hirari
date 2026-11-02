package io.github.yuubiz.hirari.tools.corpus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfCorpusTest {
    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun `every cross-reference entry points at the object it numbers`() {
        for (case in listOf(pdf(listOf(595 to 842, 300 to 400)), pdf(listOf(595 to 842), PdfPassword("a", "b")))) {
            val text = text(case)
            val xref = text.substringAfterLast("startxref\n").substringBefore('\n').toInt()
            val entries = text.substring(xref).lines().drop(3).takeWhile { it.endsWith(" n ") }
            assertTrue(entries.isNotEmpty())
            entries.forEachIndexed { i, entry ->
                assertTrue(text.startsWith("${i + 1} 0 obj\n", entry.substring(0, 10).toInt()), "object ${i + 1}")
            }
        }
    }

    @Test
    fun `a password protected pdf names its handler in the trailer`() {
        val text = text(pdfCases().getValue("pdf/password.pdf").bytes)
        assertTrue("/Filter /Standard /V 1 /R 2" in text)
        assertTrue(Regex("""/Encrypt \d+ 0 R""").containsMatchIn(text))
    }

    @Test
    fun `the pages manifest gives each pdf page its size in points`() {
        val corpus = corpus()
        val lines = text(corpus.getValue(PAGES_MANIFEST)).lines().map { it.split('\t') }
        val start = lines.indexOf(listOf("book", "pdf/mixed-sizes.pdf", "opens", "-"))
        assertEquals(
            listOf(listOf("page", "1", "ready", "595", "842"), listOf("page", "2", "ready", "842", "595"), listOf("page", "3", "ready", "300", "400")),
            lines.subList(start + 1, start + 4),
        )
        assertTrue(listOf("book", "pdf/password.pdf", "fails", "encrypted") in lines)
        assertTrue(text(corpus.getValue(MANIFEST)).lines().none { "pdf/" in it })
    }
}
