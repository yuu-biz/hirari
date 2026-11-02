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

    @Test
    fun `a line whose folded text is many times longer than the line is searched within a bounded memory`() {
        val source = "title\n" + "ﷺ".repeat(3_000_000) + "end\n"
        val result = searchText(parseAozora(source), "end")
        assertEquals(listOf("end\n"), result.hits.map { source.substring(it.start, it.end) })
        assertEquals(3_000_000 + 6, result.hits.single().start)
    }

    @Test
    fun `matches across the windows of a long line are those of the whole line`() {
        val random = java.util.Random(7)
        val line = String(CharArray(3 * SEARCH_WINDOW_CHARS + 123) { "ab"[random.nextInt(2)] })
        val source = "title\n$line\n"
        for (query in listOf("aba", "abbab", "b", "a".repeat(40), "ba".repeat(300))) {
            val expected = ArrayList<Int>()
            var at = line.indexOf(query)
            while (at >= 0 && expected.size < SEARCH_MAX_HITS) {
                expected += at + 6
                at = line.indexOf(query, at + query.length)
            }
            assertEquals(expected, searchText(parseAozora(source), query).hits.map { it.start }, query)
        }
    }

    @Test
    fun `a match that straddles the end of a window is found once`() {
        val at = SEARCH_WINDOW_CHARS - 2
        val line = "x".repeat(at) + "needle" + "x".repeat(SEARCH_WINDOW_CHARS)
        val hit = searchText(parseAozora("title\n$line\n"), "needle").hits.single()
        assertEquals(at + 6, hit.start)
        assertEquals(at + 12, hit.end)
    }

    @Test
    fun `the hit limit ends the search of a line that expands`() {
        val source = "title\n" + "ﷺ".repeat(1_000_000) + "\nend\n"
        val result = searchText(parseAozora(source), "صلى")
        assertEquals(SEARCH_MAX_HITS, result.hits.size)
        assertEquals(true, result.truncated)
    }
}
