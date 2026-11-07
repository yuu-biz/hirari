package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.text.FixedGlyphMetrics
import io.github.yuubiz.hirari.core.text.LayoutParams
import io.github.yuubiz.hirari.core.text.VerticalLayout
import io.github.yuubiz.hirari.core.text.parseAozora
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextAccessibilityTest {
    private fun pagesOf(source: String, height: Float) =
        VerticalLayout(parseAozora(source), LayoutParams(width = 336f, height = height, fontSize = 20f), FixedGlyphMetrics).pages().toList()

    @Test
    fun `a book end sheet hides the page and bars from accessibility`() {
        assertTrue(textContentAccessible(null, null))
        for (edge in BookEdge.entries) {
            assertEquals(false, textContentAccessible(null, edge))
            assertEquals(false, textContentAccessible(TextPanel.Commands, edge))
        }
        assertEquals(false, textContentAccessible(TextPanel.Search, null))
    }

    @Test
    fun `dismissing search clears its highlight while other panels do not`() {
        var clears = 0
        dismissTextPanel(TextPanel.Search) { clears++ }
        assertEquals(1, clears)
        dismissTextPanel(TextPanel.Commands) { clears++ }
        dismissTextPanel(null) { clears++ }
        assertEquals(1, clears)
    }

    @Test
    fun `a page reads its columns from the right edge to the left`() {
        val source = "一二三四五六七八九十" + "壱弐参四五六七八九拾"
        val page = pagesOf(source, height = 100f).first()
        assertTrue(page.content.let { it is io.github.yuubiz.hirari.core.text.PageContent.Lines && it.lines.size > 1 })
        assertEquals(source.take(page.plainText().length), page.plainText())
    }

    @Test
    fun `the pages of a book read back as the book`() {
        val source = (1..40).joinToString("") { "第${it}節の本文。" }
        val read = pagesOf(source, height = 160f).joinToString("") { it.plainText() }
        assertEquals(source, read)
    }
}
