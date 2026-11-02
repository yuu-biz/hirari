package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.text.FixedGlyphMetrics
import io.github.yuubiz.hirari.core.text.LayoutParams
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

    private fun PageRun.holds(index: Int, offset: Int) = offset >= start(index) && offset < end(index)

    private fun gapFree(run: PageRun) {
        assertEquals(0, run.start(0))
        assertEquals(source.length, run.end(run.lastIndex))
        for (i in 0 until run.lastIndex) assertTrue(run.start(i) < run.start(i + 1))
    }

    @Test
    fun `the first step reaches the anchor and the pages after it, before the pages that come first`() {
        val anchor = source.indexOf("名前はまだ5章の30 番目")
        val setter = Typesetter(layout, source.length, anchor)
        val near = setter.step(anchor)!!
        val first = setter.snapshot()
        assertFalse(first.fromStart)
        assertTrue(first.holds(first.indexOf(anchor), anchor))
        assertTrue(first.lastIndex - first.indexOf(anchor) >= Typesetter.LOOKAHEAD_PAGES)
        assertTrue(near.any { anchor in it }, "the step hands back the page being read")
        val number = first.number(first.indexOf(anchor))
        assertFalse(number.currentExact || number.totalExact)
        assertTrue(number.current in 2..number.total, "$number")
        while (setter.step(anchor) != null) Unit
        val all = setter.snapshot()
        assertTrue(all.fromStart && all.complete)
        gapFree(all)
        assertTrue(all.holds(all.indexOf(anchor), anchor))
        assertEquals(PageNumber(all.indexOf(anchor) + 1, all.size, currentExact = true, totalExact = true), all.number(all.indexOf(anchor)))
    }

    @Test
    fun `an anchor at the start lays out one run from the first page`() {
        val setter = Typesetter(layout, source.length, 0)
        setter.step(0)
        assertTrue(setter.snapshot().fromStart)
        while (setter.step(0) != null) Unit
        val run = setter.snapshot()
        assertEquals(layout.pages().toList().map { it.start }, (0 until run.size).map(run::start))
    }

    @Test
    fun `every page laid out comes back whole, in any order, before and after the anchor`() {
        val anchor = source.indexOf("名前はまだ4章の20 番目")
        val setter = Typesetter(layout, source.length, anchor)
        while (setter.step(anchor) != null) Unit
        val run = setter.snapshot()
        val block = layout.blockIndexAt(anchor)
        val expected = layout.pages(0, block).toList() + layout.pages(block).toList()
        assertEquals(expected.map { it.start }, (0 until run.size).map(run::start))
        val order = (0 until run.size).toList().let { it + it.reversed() + it.shuffled(kotlin.random.Random(3)) }
        for (i in order) assertEquals(expected[i], setter.page(run.start(i)), "page $i")
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
