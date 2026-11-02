package io.github.yuubiz.hirari.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerticalLayoutTest {
    private val source = sampleBook(60_000)
    private val document = parseAozora(source)

    private fun params(fontSize: Float) = LayoutParams(width = 1000f, height = 2000f, fontSize = fontSize)

    private fun layout(fontSize: Float) = VerticalLayout(document, params(fontSize), FixedGlyphMetrics)

    private fun lines(page: TextPage) = (page.content as? PageContent.Lines)?.lines.orEmpty()

    @Test
    fun `pages cover the source in order with no gap`() {
        for (size in listOf(20f, 55f)) {
            val pages = layout(size).pages().toList()
            assertEquals(0, pages.first().start)
            assertEquals(source.length, pages.last().end)
            pages.zipWithNext { a, b -> assertEquals(a.end, b.start) }
            for (page in pages) {
                for (line in lines(page)) assertTrue(line.start >= page.start && line.end <= page.end, "$line in $page")
            }
        }
    }

    @Test
    fun `everything drawn stays inside the text area`() {
        val params = params(55f)
        for (page in layout(55f).pages()) {
            for (line in lines(page)) {
                assertTrue(line.x + line.bodyWidth / 2 + line.rightBand <= params.width + 0.01f, "right edge of $line")
                assertTrue(line.x - line.bodyWidth / 2 - line.leftBand >= -0.01f, "left edge of $line")
                for (g in line.glyphs) assertTrue(g.y >= -0.01f && g.y + g.advance <= params.height + 0.01f, "$g")
                for (r in line.rubies) assertTrue(r.y >= -0.01f && r.y + r.length <= params.height + 0.01f, "$r")
            }
        }
    }

    @Test
    fun `no line starts with closing punctuation or ends with an opening bracket`() {
        for (page in layout(20f).pages()) {
            for (line in lines(page)) {
                val first = (line.glyphs.firstOrNull()?.content as? GlyphContent.Chars)?.text
                val last = (line.glyphs.lastOrNull()?.content as? GlyphContent.Chars)?.text
                val startsParagraph = document.blocks.any { it.start == line.start }
                if (!startsParagraph) assertTrue(first == null || first.first() !in "、。」）", "line starts with $first")
                assertTrue(last == null || last.last() !in "「（", "line ends with $last")
            }
        }
    }

    @Test
    fun `a ruby never leaves its base`() {
        for (page in layout(20f).pages()) {
            for (line in lines(page)) {
                for (ruby in line.rubies) {
                    assertTrue(line.glyphs.any { it.y <= ruby.y + ruby.length && it.y + it.advance >= ruby.y }, "$ruby")
                }
            }
        }
    }

    // docs/spec.md section 9, M5: after a size change the screen shows the same sentence as before.
    @Test
    fun `a re-layout from a position at another size starts on the page that holds it`() {
        val before = layout(20f).pages().toList()
        val after = layout(30f)
        for (page in before.filterIndexed { i, _ -> i % 7 == 3 }) {
            val offset = (page.start + page.end) / 2
            val anchor = after.blockIndexAt(offset)
            val shown = after.pages(anchor).first { offset in it }
            assertTrue(lines(shown).any { line -> line.glyphs.any { offset in it.start until it.end } || offset in line.start until line.end }, "$offset")
            val whole = after.pages(0, anchor).toList() + after.pages(anchor).toList()
            assertEquals(0, whole.first().start)
            assertEquals(source.length, whole.last().end)
            whole.zipWithNext { a, b -> assertEquals(a.end, b.start) }
        }
    }

    @Test
    fun `the first page comes without laying out the rest`() {
        var laidOut = 0
        val counting = GlyphMetrics { text, size, bold -> laidOut++; FixedGlyphMetrics.width(text, size, bold) }
        val first = VerticalLayout(document, params(20f), counting).pages().first()
        val firstOnly = laidOut
        val all = VerticalLayout(document, params(20f), counting).pages().toList()
        assertEquals(all.first(), first)
        assertTrue(firstOnly * 10 < laidOut - firstOnly, "first page measured $firstOnly of ${laidOut - firstOnly}")
    }

    @Test
    fun `a ruby base of tens of thousands of characters splits without overflowing`() {
        val doc = parseAozora("題\n｜" + "漢".repeat(50_000) + "《あ》\n")
        val tiny = LayoutParams(width = 100f, height = 10f, fontSize = 10f)
        val first = VerticalLayout(doc, tiny, FixedGlyphMetrics).pages().first()
        assertEquals(0, first.start)
    }

    @Test
    fun `a re-layout starts at the beginning of a source line even when notes split the line`() {
        val text = "題\n前の行\n［＃ページの左右中央］本文です\n行の途中［＃改ページ］続き\n"
        val doc = parseAozora(text)
        val params = LayoutParams(width = 100f, height = 100f, fontSize = 10f)
        val layout = VerticalLayout(doc, params, FixedGlyphMetrics)
        for (offset in text.indices) {
            val block = doc.blocks[layout.blockIndexAt(offset)]
            val lineStart = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
            assertEquals(lineStart, block.start, "offset $offset")
        }
        val offset = text.indexOf("本文")
        val page = layout.pages(layout.blockIndexAt(offset)).first { offset in it }
        val lines = lines(page)
        val right = lines.maxOf { it.x + it.bodyWidth / 2 }
        val left = lines.minOf { it.x - it.bodyWidth / 2 }
        assertEquals(params.width / 2, (right + left) / 2, 0.01f)
    }

    @Test
    fun `an empty book has one empty page`() {
        assertEquals(listOf(TextPage(PageContent.Lines(emptyList()), 0, 0)), VerticalLayout(parseAozora(""), params(20f), FixedGlyphMetrics).pages().toList())
    }
}
