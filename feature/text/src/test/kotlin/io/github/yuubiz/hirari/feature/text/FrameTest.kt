package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BarDisplay
import io.github.yuubiz.hirari.core.model.TextLayout
import io.github.yuubiz.hirari.core.model.TextMargin
import io.github.yuubiz.hirari.core.text.FixedGlyphMetrics
import io.github.yuubiz.hirari.core.text.PageContent
import io.github.yuubiz.hirari.core.text.VerticalLayout
import io.github.yuubiz.hirari.core.text.parseAozora
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameTest {
    private val large = GlobalSettings(barDisplay = BarDisplay.Hidden, textSizeSp = 62, textMargin = TextMargin.Wide, textLayout = TextLayout.Single)

    private fun assertInside(frame: PageFrame) {
        assertTrue(frame.margin >= 0f)
        assertTrue(frame.margin * 2 + frame.params.width <= frame.pageWidth + 0.01f)
        assertTrue(frame.margin * 2 + frame.band * 2 + frame.params.height <= frame.pageHeight + 0.01f)
        assertEquals(62f, frame.params.fontSize)
    }

    @Test
    fun `TX04 62sp on a 360dp tall screen reduces margins before hiding information`() {
        val frame = pageFrame(Viewport(400, 360, 1f), large)
        assertInside(frame)
        assertTrue(frame.margin < TextMargin.Wide.dp)
        assertTrue(frame.band > 0f)
    }

    @Test
    fun `TX04 information is hidden only after margins reach zero`() {
        val frame = pageFrame(Viewport(400, 240, 1f), large)
        assertInside(frame)
        assertEquals(0f, frame.margin)
        assertEquals(0f, frame.band)
    }

    @Test
    fun `TX04 the last resort keeps the chosen size and uses one line per page`() {
        val frame = pageFrame(Viewport(400, 100, 1f), large)
        assertInside(frame)
        assertEquals(0f, frame.margin)
        assertEquals(0f, frame.band)
        val document = parseAozora("題\n" + "本文を読み続ける。".repeat(10))
        val pages = VerticalLayout(document, frame.params, FixedGlyphMetrics).pages().toList()
        assertTrue(pages.size > 2)
        assertTrue(pages.all { (it.content as PageContent.Lines).lines.size == 1 })
        assertEquals(document.sourceLength, pages.last().end)
    }
}
