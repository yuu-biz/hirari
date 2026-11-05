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
