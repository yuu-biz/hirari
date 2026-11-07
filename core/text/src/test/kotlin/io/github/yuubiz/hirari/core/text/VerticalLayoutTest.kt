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
    fun `ruby and right emphasis do not overlap even at a page end`() {
        val source = "題\n一二｜漢《かん》［＃「漢」に傍点］"
        val params = LayoutParams(width = 16f, height = 36f, fontSize = 10f, letterGap = 0f)
        val layout = VerticalLayout(parseAozora(source), params, FixedGlyphMetrics)
        val pages = layout.markedPages().toList()
        val marked = pages.single { lines(it.page).any { line -> line.emphasis.isNotEmpty() } }
        val line = lines(marked.page).single { it.emphasis.isNotEmpty() }
        val ruby = line.rubies.single()
        val mark = line.emphasis.single()
        assertTrue(mark.centers.single() - mark.size / 2 >= ruby.y + ruby.length - 0.01f)
        assertTrue(mark.centers.single() + mark.size / 2 <= params.height + 0.01f)
        // The decorated character would fit after 一二 without reserving its displaced mark.
        assertEquals(1, line.glyphs.size)
        assertEquals(marked.page, layout.resume(marked.mark).first().page)
    }

    @Test
    fun `an emphasis annotation before ruby moves the ruby instead`() {
        val source = "題\n｜漢［＃「漢」に傍点］《かん》"
        val pageLines = VerticalLayout(parseAozora(source), LayoutParams(100f, 100f, 10f), FixedGlyphMetrics)
            .pages().flatMap { lines(it) }.toList()
        val line = pageLines.single { it.emphasis.isNotEmpty() }
        val mark = line.emphasis.single()
        assertTrue(line.rubies.single().y >= mark.centers.single() + mark.size / 2 - 0.01f)
    }

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
    fun `the first page of one huge line is laid out without the rest of the line`() {
        val line = "Hello world 漢字《かんじ》の「話」。".repeat(20_000)
        val huge = parseAozora("題\n$line\n")
        var laidOut = 0
        val counting = GlyphMetrics { text, size, bold -> laidOut++; FixedGlyphMetrics.width(text, size, bold) }
        val first = VerticalLayout(huge, params(20f), counting).pages().first()
        val firstOnly = laidOut
        val all = VerticalLayout(huge, params(20f), counting).pages().toList()
        assertEquals(all.first(), first)
        assertTrue(firstOnly * 20 < laidOut - firstOnly, "first page measured $firstOnly of ${laidOut - firstOnly}")
        all.zipWithNext { a, b -> assertEquals(a.end, b.start) }
        assertEquals(huge.sourceLength, all.last().end)
    }

    @Test
    fun `a long paragraph of only sideways words is measured only as far as the first page needs`() {
        fun measuredForFirstPage(repeats: Int, mode: LatinMode): Int {
            var calls = 0
            val counting = GlyphMetrics { text, size, bold -> calls++; FixedGlyphMetrics.width(text, size, bold) }
            val doc = parseAozora("題\n" + "Hello world ".repeat(repeats))
            VerticalLayout(doc, params(20f).copy(latinMode = mode), counting).pages().first()
            return calls
        }
        for (mode in listOf(LatinMode.ShortTateChuYoko, LatinMode.Sideways)) {
            val short = measuredForFirstPage(1_000, mode)
            val long = measuredForFirstPage(100_000, mode)
            assertEquals(short, long, "$mode: first page measured $short for 1k words and $long for 100k")
        }
    }

    @Test
    fun `a single word longer than a column splits one character at a time as the lines ask`() {
        fun measuredForFirstPage(length: Int): Int {
            var calls = 0
            val counting = GlyphMetrics { text, size, bold -> calls++; FixedGlyphMetrics.width(text, size, bold) }
            val doc = parseAozora("題\n" + "a".repeat(length))
            val pages = VerticalLayout(doc, params(20f).copy(latinMode = LatinMode.Sideways), counting).pages()
            val first = pages.first()
            assertEquals(0, first.start)
            return calls
        }
        assertEquals(measuredForFirstPage(10_000), measuredForFirstPage(200_000))
    }

    @Test
    fun `each Latin mode sets numbers, pairs and words as the spec says`() {
        val text = "題\nあ12い345う!!x??え89 abc\n"
        fun shown(mode: LatinMode): String {
            val params = LayoutParams(width = 200f, height = 400f, fontSize = 10f, latinMode = mode)
            val pages = VerticalLayout(parseAozora(text), params, FixedGlyphMetrics).pages().toList()
            return pages.flatMap { lines(it) }.flatMap { it.glyphs }.drop(1).joinToString("|") { glyph ->
                val chars = glyph.content as GlyphContent.Chars
                "${chars.text}:${chars.orientation.name.first()}"
            }
        }
        assertEquals("あ:U|12:T|い:U|345:S|う:U|!!x??:S|え:U|89 :S|abc:S", shown(LatinMode.ShortTateChuYoko))
        assertEquals("あ:U|12:S|い:U|345:S|う:U|!!x??:S|え:U|89 :S|abc:S", shown(LatinMode.Sideways))
        assertEquals("あ:U|12:T|い:U|3:U|4:U|5:U|う:U|!!:T|x:U|??:T|え:U|89:T| :U|a:U|b:U|c:U", shown(LatinMode.Upright))
    }

    private fun placedLines(doc: TextDocument, params: LayoutParams): List<List<TextLine>> =
        VerticalLayout(doc, params, FixedGlyphMetrics).pages().map { lines(it) }.toList()

    private fun TextLine.text() = glyphs.joinToString("") { (it.content as GlyphContent.Chars).text }

    @Test
    fun `a bottom-aligned part that wraps starts on the next line and only the last line meets the bottom`() {
        val tight = LayoutParams(width = 1000f, height = 50f, fontSize = 10f, letterGap = 0f)
        val lines = placedLines(parseAozora("題\n本文［＃地付き］あいうえお、かき"), tight).flatten()
        assertEquals(listOf("題", "本文", "あいうえ", "お、かき"), lines.map { it.text() })
        assertEquals(listOf(0f, 0f), lines.subList(1, 3).map { it.glyphs.first().y })
        assertEquals(10f, lines[3].glyphs.first().y)
        assertEquals(50f, lines[3].glyphs.last().let { it.y + it.advance })
    }

    @Test
    fun `lines of a bottom-aligned part before the last stay at the top across a page boundary`() {
        val tight = LayoutParams(width = 40f, height = 50f, fontSize = 10f, letterGap = 0f)
        val pages = placedLines(parseAozora("題\n本文［＃地付き］あいうえお、かきくけこさし"), tight)
        assertEquals(listOf(listOf("題", "本文", "あいうえ"), listOf("お、かきく", "けこさし")), pages.map { page -> page.map { it.text() } })
        assertEquals(listOf(0f, 0f), pages[0].drop(1).map { it.glyphs.first().y })
        assertEquals(0f, pages[1][0].glyphs.first().y)
        assertEquals(10f, pages[1][1].glyphs.first().y)
    }

    @Test
    fun `a bottom-aligned part that fits after the text on the same line is still joined to its line`() {
        val tight = LayoutParams(width = 1000f, height = 100f, fontSize = 10f, letterGap = 0f)
        val lines = placedLines(parseAozora("題\n本文［＃地付き］署名"), tight).flatten()
        assertEquals(listOf("題", "本文署名"), lines.map { it.text() })
        assertEquals(80f, lines[1].glyphs[2].y)
    }

    @Test
    fun `an empty book has one empty page`() {
        assertEquals(listOf(TextPage(PageContent.Lines(emptyList()), 0, 0)), VerticalLayout(parseAozora(""), params(20f), FixedGlyphMetrics).pages().toList())
    }
}
