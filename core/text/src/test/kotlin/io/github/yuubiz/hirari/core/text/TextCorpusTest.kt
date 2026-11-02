package io.github.yuubiz.hirari.core.text

import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each file of the text corpus (docs/spec.md section 6.2) against the charset and text its manifest expects. */
class TextCorpusTest {
    private val dir = File(System.getProperty("hirari.textCorpus") ?: error("run through Gradle, which generates the corpus"))

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `every corpus text is detected and decoded as its manifest says`() {
        val lines = dir.resolve("text.tsv").readLines().filter { it.isNotEmpty() }
        assertTrue(lines.size >= 9)
        for (line in lines) {
            val (path, charset, length, hash) = line.split('\t')
            val bytes = dir.resolve(path).readBytes()
            val detected = detectCharset(bytes, fallback = Charset.forName("GBK"))
            assertEquals(Charset.forName(charset), detected.charset, path)
            val text = decodeText(bytes, detected.charset)
            assertEquals(length.toInt(), text.length, path)
            assertEquals(hash, sha256(text), path)
            assertTrue(VerticalLayout(parseAozora(text), LayoutParams(1000f, 2000f, 40f), FixedGlyphMetrics).pages().any(), path)
        }
    }
}
