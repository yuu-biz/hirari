package io.github.yuubiz.hirari.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryNamesTest {
    @Test
    fun `series keys strip all supported volume suffixes but retain number only titles`() {
        val cases = listOf(
            "題 第12巻" to "題", "題 第十二話" to "題", "題 １２巻" to "題",
            "題上" to "題", "題 中" to "題", "題下" to "題", "題前編" to "題", "題後編" to "題",
            "Title vol.12" to "title", "Title Vol １２" to "title", "Title v 二" to "title",
            "Title #12" to "title", "Title (１２)" to "title", "Title（十二）" to "title",
            "星降る丘15" to "星降る丘", "題十五" to "題", "題１２" to "題",
            "(C108) [Circle (Author)] 星降る丘15 (オリジナル) [DL版]" to "星降る丘",
            "（コミティア150）［Circle（Author）］題 第三巻（原作）［翻訳］［DL版］" to "題",
            "Plain Title" to "plain title", "素の題名" to "素の題名", "Title (原作) [DL版]" to "title",
            "123" to "123", "１２３" to "123", "十五" to "十五", "" to "",
        )
        for ((name, expected) in cases) {
            assertEquals(expected, normalizedName(seriesTitle(parseLibraryName(name).title)), name)
        }
        assertEquals("Ｓｔｏｒｙ", seriesTitle(parseLibraryName("[A] Ｓｔｏｒｙ 第２巻 (原作)").title))
    }

    @Test
    fun `event circle and author are read without changing the full creator spelling`() {
        val parsed = parseLibraryName("(C108) [ひだまり工房 (こもれび)] 星降る丘15 (オリジナル) [DL版]")
        assertEquals("C108", parsed.event)
        assertEquals("ひだまり工房 (こもれび)", parsed.creator)
        assertEquals("ひだまり工房", parsed.circle)
        assertEquals("こもれび", parsed.author)
        assertEquals("星降る丘15", parsed.title)
        assertEquals("オリジナル", parsed.original)
        assertEquals(listOf("ひだまり工房", "こもれび"), parsed.names)
    }

    @Test
    fun `full width no event no spaces and trailing tags use the same creator`() {
        val names = listOf(
            "（ C108 ）［ ひだまり工房 （ こもれび ） ］星降る丘15（オリジナル）［DL版］" to "C108",
            "[ひだまり工房 (こもれび)] 星降る丘15 (オリジナル) [DL版]" to null,
            "(C108)[ひだまり工房(こもれび)]星降る丘15[翻訳版][DL版]" to "C108",
            "[ひだまり工房 (こもれび)] 題 [翻訳版] [DL版]" to null,
        )
        for ((name, event) in names) {
            val parsed = parseLibraryName(name)
            assertEquals(event, parsed.event, name)
            assertEquals("ひだまり工房", parsed.circle, name)
            assertEquals("こもれび", parsed.author, name)
            assertEquals(parsed.creator, authorInName(name), name)
            assertEquals(parsed.creator, splitBrackets(name).note, name)
        }
    }

    @Test
    fun `a circle or an author alone has an unknown role`() {
        for (single in listOf("ひだまり工房", "こもれび")) {
            val parsed = parseLibraryName("[ $single ] 題")
            assertEquals(single, parsed.creator)
            assertEquals(listOf(single), parsed.names)
            assertNull(parsed.circle)
            assertNull(parsed.author)
        }
    }

    @Test
    fun `missing or unbalanced brackets preserve existing extraction`() {
        for (name in listOf("", "題 (原作 (副題))", "(C108) 題", "[閉じない 題", "［閉じない] 題")) {
            assertNull(parseLibraryName(name).creator, name)
            assertNull(authorInName(name), name)
        }
        assertEquals("first", parseLibraryName("[unclosed ［first］ 題").creator)
        assertEquals("全角 [半角] 題", parseLibraryName("［全角 [半角] 題］").creator)
        for (creator in listOf("Circle (Author", "Circle Author)", "Circle （Author)", "Circle ()", "(Author)")) {
            val parsed = parseLibraryName("[$creator] 題")
            assertEquals(creator, parsed.creator)
            assertEquals(listOf(creator), parsed.names)
        }
    }

    @Test
    fun `title parentheses never become creator parts and last outer creator parentheses win`() {
        val parsed = parseLibraryName("(C108) [Circle (old) (Author)] 題 (原作 (副題)) [DL版]")
        assertEquals("C108", parsed.event)
        assertEquals("Circle (old)", parsed.circle)
        assertEquals("Author", parsed.author)
        assertEquals("Author (alias)", parseLibraryName("[Circle (Author (alias))] 題").author)
        assertEquals(listOf("Circle"), parseLibraryName("[Circle] 題 (原作 (副題))").names)
        assertNull(parseLibraryName("(unclosed [Circle (Author)] 題").event)
        assertEquals("C108", parseLibraryName("(C108) 題 (unclosed").event)
    }
}
