package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.text.FixedGlyphMetrics
import io.github.yuubiz.hirari.core.text.LayoutParams
import io.github.yuubiz.hirari.core.text.TextPage
import io.github.yuubiz.hirari.core.text.VerticalLayout
import io.github.yuubiz.hirari.core.text.parseAozora
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypesetterTest {
    private val source = sampleText(chapters = 6)
    private val document = parseAozora(source)
    private val layout = VerticalLayout(document, LayoutParams(width = 336f, height = 472f, fontSize = 20f), FixedGlyphMetrics)

    private fun gapFree(pages: List<TextPage>) {
        assertEquals(0, pages.first().start)
        assertEquals(source.length, pages.last().end)
        pages.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
    }

    @Test
    fun `the first step reaches the anchor and the pages after it, before the pages that come first`() {
        val anchor = source.indexOf("名前はまだ5章の30 番目")
        val setter = Typesetter(layout, source.length, anchor)
        assertTrue(setter.step(anchor))
        val first = setter.snapshot()
        assertFalse(first.fromStart)
        assertTrue(anchor in first.pages[first.indexOf(anchor)])
        assertTrue(first.pages.size - 1 - first.indexOf(anchor) >= Typesetter.LOOKAHEAD_PAGES)
        val number = first.number(first.indexOf(anchor))
        assertFalse(number.currentExact || number.totalExact)
        assertTrue(number.current in 2..number.total, "$number")
        while (setter.step(anchor)) Unit
        val all = setter.snapshot()
        assertTrue(all.fromStart && all.complete)
        gapFree(all.pages)
        assertTrue(anchor in all.pages[all.indexOf(anchor)])
        assertEquals(PageNumber(all.indexOf(anchor) + 1, all.pages.size, currentExact = true, totalExact = true), all.number(all.indexOf(anchor)))
    }

    @Test
    fun `an anchor at the start lays out one run from the first page`() {
        val setter = Typesetter(layout, source.length, 0)
        assertTrue(setter.step(0))
        assertTrue(setter.snapshot().fromStart)
        while (setter.step(0)) Unit
        assertEquals(layout.pages().toList().map { it.start }, setter.snapshot().pages.map { it.start })
    }

    @Test
    fun `a run covers only offsets it can show without a new layout`() {
        val anchor = source.indexOf("名前はまだ3章の0 番目")
        val setter = Typesetter(layout, source.length, anchor)
        setter.step(anchor)
        val run = setter.snapshot()
        assertTrue(run.covers(anchor))
        assertFalse(run.covers(0))
        assertFalse(run.covers(source.length - 1))
    }
}
