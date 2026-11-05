package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.LibrarySort
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FileGroupsTest {
    private fun entry(name: String, folder: Boolean = false, seconds: Long = 0) =
        FolderEntry(name, name, folder, 1, Instant.ofEpochSecond(seconds))

    private fun rows(vararg names: String) = names.map { entry(it) }.toRows("folder", Ordering(LibrarySort.Name, false, false))

    private fun group(rows: List<FileRow>, parent: Boolean = false, folded: Set<String> = emptySet()) =
        fileCells(rows, LibraryGrouping.Author, parent, folded)

    private fun List<ListCell>.headers() = filterIsInstance<ListCell.Header>()
    private fun List<ListCell>.names() = filterIsInstance<ListCell.Entry>().map { it.row.item.entry.name }

    @Test
    fun `circle only author only and both merge while keeping list order and folding`() {
        val input = rows("[Circle] 1.zip", "[Author] 2.zip", "(C108) [Circle (Author)] 3.zip")
        val cells = group(input, parent = true)
        val header = cells.headers().single()
        assertEquals("Circle (Author)", header.author)
        assertEquals(3, header.count)
        assertEquals(input.map { it.item.entry.name }, cells.names())
        assertEquals(listOf(ListCell.Parent, header), group(input, parent = true, folded = setOf(header.key)))
    }

    @Test
    fun `shared authors merge circles transitively with normalized single names`() {
        val cells = group(rows("[Circle1 (Author)] 1.zip", "［Ｃｉｒｃｌｅ２ （ＡＵＴＨＯＲ）］ 2.zip",
            "[circle2 (Other)] 3.zip", "[other] 4.zip", "[ CIRCLE1 ] 5.zip"))
        assertEquals(5, cells.headers().single().count)
        assertEquals("/author:author", cells.headers().single().key)
    }

    @Test
    fun `off keeps complete creator groups apart and retains first spelling`() {
        val input = rows("[Circle] 1.zip", "[Author] 2.zip", "[Circle (Author)] 3.zip", "［ＣＩＲＣＬＥ］ 4.zip")
        val cells = fileCells(input, LibraryGrouping.Author, false, mergeCircleAuthors = false)
        assertEquals(listOf(1, 2, 1), cells.headers().map { it.count })
        assertEquals(listOf("Author", "Circle", "Circle (Author)"), cells.headers().map { it.author })
    }

    @Test
    fun `header prefers both parts then the most frequent exact spelling with first appearance breaking ties`() {
        fun header(vararg names: String) = group(names.map { entry(it) }.toRows("", Ordering(LibrarySort.Name, false, false)))
            .headers().single().author
        assertEquals("Circle (Author)", header("[Author] 1.zip", "[Author] 2.zip", "[Circle (Author)] 3.zip"))
        assertEquals("Circle (Author)", header("［Ｃｉｒｃｌｅ （Ａｕｔｈｏｒ）］ 1.zip", "[Circle (Author)] 2.zip", "[Circle (Author)] 3.zip"))
        val input = rows("[Circle (Author)] 1.zip", "［Ｃｉｒｃｌｅ （Ａｕｔｈｏｒ）］ 2.zip")
        assertEquals(authorInName(input.first().item.entry.name), group(input).headers().single().author)
        assertEquals(authorInName(input.last().item.entry.name), group(input.reversed()).headers().single().author)
        assertEquals("author", header("[Author] 1.zip", "[author] 2.zip", "[author] 3.zip"))
    }

    @Test
    fun `keys survive filtering and spelling changes when the smallest normalized name remains`() {
        val input = rows("[Circle (Author)] keep1.zip", "［Ｃｉｒｃｌｅ （Ａｕｔｈｏｒ）］ keep2.zip", "[author] extra.zip")
        val key = group(input).headers().single().key
        assertEquals("/author:author", key)
        assertEquals(key, group(input.filtered("keep")).headers().single().key)
        assertEquals(key, group(input.filtered("keep2")).headers().single().key)
        assertEquals(key, group(input.filtered("extra")).headers().single().key)
        assertEquals(key, group(input.reversed()).headers().single().key)
    }

    @Test
    fun `natural header order uses the chosen spelling rather than the component key`() {
        val cells = group(rows("[Circle10 (A)] 1.zip", "[Circle2 (Z)] 2.zip", "plain.zip"), parent = true)
        assertEquals(listOf("Circle2 (Z)", "Circle10 (A)", null), cells.headers().map { it.author })
    }

    @Test
    fun `filtered out links do not merge otherwise unrelated names`() {
        val input = rows("[Circle] keep.zip", "[Author] keep.zip", "[Circle (Author)] remove.zip")
        assertEquals(1, group(input).headers().size)
        assertEquals(2, group(input.filtered("keep")).headers().size)
    }

    @Test
    fun `bracket variants NFKC spaces and case folding share a stable key and first spelling`() {
        val names = listOf("［ Ａｕｔｈｏｒ ］ 3.zip", "[author] 2.zip", "[ Author ] 1.zip")
        val input = names.map { entry(it) }.toRows("", Ordering(LibrarySort.Newest, false, false)).reversed()
        val cells = group(input)
        val header = cells.headers().single()
        assertEquals(authorInName(input.first().item.entry.name), header.author)
        assertEquals(3, header.count)
        assertEquals(input.map { it.item.entry.name }, cells.names())
        assertEquals(header.key, group(input.reversed()).headers().single().key)
        assertEquals(1, group(rows("[Straße] 1.zip", "[STRASSE] 2.zip")).headers().size)
        assertEquals(1, group(rows("[Σ] 1.zip", "[ς] 2.zip")).headers().size)
    }

    @Test
    fun `first complete bracket pair is the author including names made only of brackets`() {
        val cells = group(rows("[first] [second].zip", "［first］.zip", "[unclosed ［first］.zip"))
        assertEquals(listOf("first"), cells.headers().map { it.author })
        assertEquals(3, cells.headers().single().count)
    }

    @Test
    fun `parent stays first authors use natural ascending order and no author comes last`() {
        val cells = group(rows("[author10] a.zip", "plain.zip", "[author2] b.zip", "[unclosed.zip"), parent = true)
        assertIs<ListCell.Parent>(cells.first())
        assertEquals("..", cells.first().key)
        assertEquals(listOf("author2", "author10", null), cells.headers().map { it.author })
        assertEquals(2, cells.headers().last().count)
        assertEquals(cells.size, cells.map { it.key }.toSet().size)
    }

    @Test
    fun `all item kinds share groups and keep current date sort with folders first`() {
        val input = listOf(entry("[A] old.zip", seconds = 1), entry("[B] b.zip", seconds = 10),
            entry("［a］ folder", folder = true), entry("[a] new.txt", seconds = 3), entry("[A] image.jpg", seconds = 2))
            .toRows("", Ordering(LibrarySort.Newest, true, false))
        val cells = group(input)
        assertEquals(listOf("［a］ folder", "[a] new.txt", "[A] image.jpg", "[A] old.zip", "[B] b.zip"), cells.names())
        assertEquals(listOf(4, 1), cells.headers().map { it.count })
    }

    @Test
    fun `filter precedes grouping so hidden authors and items do not affect headers or counts`() {
        val input = rows("[A] keep.zip", "[A] remove.zip", "[B] remove.zip", "keep.txt").filtered("ＫＥＥＰ")
        val cells = group(input, parent = true)
        assertEquals(listOf("A", null), cells.headers().map { it.author })
        assertEquals(listOf(1, 1), cells.headers().map { it.count })
        assertEquals(2, cells.names().size)
        assertEquals(listOf(ListCell.Parent), group(input.filtered("absent"), parent = true))
    }

    @Test
    fun `folding removes only entries retains count and cannot confuse no author with a literal label`() {
        val input = rows("[作者なし] a.zip", "plain.zip", "[A] b.zip")
        val cells = group(input)
        val noAuthor = cells.headers().last()
        assertNotEquals(noAuthor.key, cells.headers().first { it.author == "作者なし" }.key)
        val folded = group(input, folded = setOf(noAuthor.key))
        assertEquals(cells.headers(), folded.headers())
        assertTrue("plain.zip" !in folded.names())
        assertEquals(2, folded.names().size)
    }

    @Test
    fun `none keeps the original list with no headers even if a group was folded`() {
        val input = rows("[B] b.zip", "[A] a.zip", "plain.zip").reversed()
        val cells = fileCells(input, LibraryGrouping.None, true, setOf("/author:a"))
        assertEquals(input.map { it.path }, cells.drop(1).map { it.key })
        assertTrue(cells.headers().isEmpty())
    }
}
