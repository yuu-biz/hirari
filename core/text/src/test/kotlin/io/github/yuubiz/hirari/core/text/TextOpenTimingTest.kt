package io.github.yuubiz.hirari.core.text

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A JVM proxy for P9 of docs/spec.md section 5.2: a 1 MB CP932 text to its first page, and to its whole layout.
 * It runs on the build machine with [FixedGlyphMetrics], so its numbers say nothing about a phone; they guard
 * against the engine growing a cost that grows with the book. The device number comes from the screen's trace section.
 */
class TextOpenTimingTest {
    private val bytes = sampleBook(700_000).toByteArray(CP932).also { require(it.size >= 1 shl 20) }.copyOf(1 shl 20)
    private val params = LayoutParams(width = 1000f, height = 2100f, fontSize = 55f)

    /** Nanoseconds for decoding, parsing, the first page, and the remaining pages, and the page count. */
    private fun open(): Pair<LongArray, Int> {
        val t0 = System.nanoTime()
        val text = decodeText(bytes, detectCharset(bytes, CP932).charset)
        val t1 = System.nanoTime()
        val document = parseAozora(text)
        val t2 = System.nanoTime()
        val pages = VerticalLayout(document, params, FixedGlyphMetrics).pages().iterator()
        pages.next()
        val t3 = System.nanoTime()
        var count = 1
        while (pages.hasNext()) {
            pages.next()
            count++
        }
        val t4 = System.nanoTime()
        return longArrayOf(t1 - t0, t2 - t1, t3 - t2, t4 - t3) to count
    }

    @Test
    fun `a 1 MB text opens and lays out within a bound`() {
        repeat(5) { open() }
        val runs = List(7) { open() }
        val pages = runs.first().second
        val median = { stage: Int -> runs.map { it.first[stage] }.sorted()[runs.size / 2] / 1_000_000.0 }
        val (decode, parse, first, rest) = (0..3).map(median)
        println(
            "P9 proxy (JVM, fixed metrics, ${bytes.size} bytes, $pages pages, median of ${runs.size}): " +
                "decode $decode ms, parse $parse ms, first page $first ms, remaining pages $rest ms",
        )
        val firstPage = decode + parse + first
        val whole = firstPage + rest
        assertTrue(firstPage < 3_000 && whole < 30_000, "first page $firstPage ms, whole layout $whole ms")
    }
}
