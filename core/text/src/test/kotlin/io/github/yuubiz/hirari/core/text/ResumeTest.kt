package io.github.yuubiz.hirari.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A page laid out again from its mark is the page the whole layout made, whatever the page starts with. */
class ResumeTest {
    private val small = LayoutParams(width = 100f, height = 100f, fontSize = 10f)

    private fun golden(name: String) = decodeText(javaClass.getResource("/golden/$name.txt")!!.readBytes(), Charsets.UTF_8)

    /** Every page of blocks `from until to` comes back from its mark, and so does every page after it. */
    private fun assertResumes(layout: VerticalLayout, from: Int, to: Int, whole: Boolean) {
        val marked = layout.markedPages(from, to).toList()
        for ((i, page) in marked.withIndex()) {
            assertEquals(page.mark.start, page.page.start)
            val again = layout.resume(page.mark).map { it.page }
            if (whole) {
                assertEquals(marked.drop(i).map { it.page }, again.toList(), "from page $i")
            } else {
                assertEquals(page.page, again.first(), "page $i")
            }
        }
    }

    @Test
    fun `the golden pages come back from their marks`() {
        for (name in listOf("ruby", "kinsoku", "indent", "bottom", "heading", "emphasis", "pages", "characters", "latin")) {
            val layout = VerticalLayout(parseAozora(golden(name)), small, FixedGlyphMetrics)
            assertResumes(layout, 0, parseAozora(golden(name)).blocks.size, whole = true)
        }
    }

    @Test
    fun `pages after a break, a centered page, a picture and inside a long paragraph come back`() {
        val text = "題\n" + "本文の段落。".repeat(40) + "\n［＃改ページ］\n［＃ページの左右中央］\n中央の行\n［＃挿絵（a.png）入る］\n" +
            "［＃ページの左右中央］\n" + "図の後の段落。".repeat(30) + "\n［＃改ページ］\n［＃挿絵（b.png）入る］\n終わり\n"
        val document = parseAozora(text)
        val layout = VerticalLayout(document, small, FixedGlyphMetrics)
        val pages = layout.pages().toList()
        assertTrue(pages.any { it.content is PageContent.Picture })
        assertTrue(pages.count { page -> document.blocks.none { it is Paragraph && it.start == page.start } } > 3, "pages inside a paragraph")
        assertResumes(layout, 0, document.blocks.size, whole = true)
        for (anchor in listOf(text.indexOf("中央の行"), text.indexOf("図の後") + 100, text.indexOf("終わり"))) {
            val block = layout.blockIndexAt(anchor)
            assertResumes(layout, 0, block, whole = true)
            assertResumes(layout, block, document.blocks.size, whole = true)
        }
    }

    @Test
    fun `the pages of a book come back from their marks at two sizes`() {
        val document = parseAozora(sampleBook(60_000))
        for (size in listOf(20f, 55f)) {
            val layout = VerticalLayout(document, LayoutParams(width = 1000f, height = 2000f, fontSize = size), FixedGlyphMetrics)
            assertResumes(layout, 0, document.blocks.size, whole = false)
        }
    }
}
