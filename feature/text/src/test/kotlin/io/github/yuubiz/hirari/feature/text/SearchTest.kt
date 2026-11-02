package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.text.parseAozora
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTest {
    private fun ranges(source: String, query: String) =
        searchText(parseAozora(source), query).hits.map { source.substring(it.start, it.end) }

    @Test
    fun `half-width kana with voiced marks match their full-width forms both ways`() {
        val source = "題\n新しいｶﾞｲﾄﾞを読む。\n"
        assertEquals(listOf("ｶﾞｲﾄﾞ"), ranges(source, "ガイド"))
        assertEquals(listOf("ｶﾞｲﾄﾞ"), ranges(source, "ｶﾞｲﾄﾞ"))
        assertEquals(listOf("ﾄﾞ"), ranges(source, "ド"))
        assertEquals(emptyList(), ranges(source, "ト"))
        assertEquals(listOf("ガイド"), ranges("題\nガイドを読む。\n", "ｶﾞｲﾄﾞ"))
    }

    @Test
    fun `a kana with a combining voiced mark matches the composed kana both ways`() {
        assertEquals(listOf("が"), ranges("題\nがくもん\n", "が"))
        assertEquals(listOf("が"), ranges("題\nがくもん\n", "が"))
        assertEquals(listOf("パン"), ranges("題\nパンを焼く\n", "パン"))
    }

    @Test
    fun `the context marks the match in the source characters`() {
        val hit = searchText(parseAozora("題\n新しいｶﾞｲﾄﾞを読む。\n"), "ガイド").hits.single()
        assertEquals("ｶﾞｲﾄﾞ", hit.context.substring(hit.matchStart, hit.matchEnd))
    }

    @Test
    fun `a match on one half of a kuno-ji-ten marks the pair`() {
        val source = "title\n／＼abc\n"
        assertEquals(listOf("／＼"), ranges(source, "〳"))
        assertEquals(listOf("／＼"), ranges(source, "〵"))
        assertEquals(listOf("／＼"), ranges(source, "〳〵"))
    }
}
