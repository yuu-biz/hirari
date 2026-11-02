package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.LibraryBook
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FileItemsTest {
    private val byName = Ordering(LibrarySort.Name, foldersFirst = true, showDotFiles = false)

    private fun file(name: String, modified: Instant? = null) = FolderEntry(name, "doc:$name", directory = false, size = 1, modified = modified)

    private fun dir(name: String, modified: Instant? = null) = FolderEntry(name, "doc:$name", directory = true, size = null, modified = modified)

    private fun List<FileRow>.names() = map { it.item.entry.name }

    @Test
    fun `the list keeps folders, books, texts and images and leaves out other and hidden files`() {
        val rows = listOf(file("a.cbz"), file("b.mp4"), file(".hidden.zip"), file("c.TXT"), file("d.JPG"), dir("e"), dir(".git")).toRows("", byName)

        assertEquals(listOf("e", "a.cbz", "c.TXT", "d.JPG"), rows.names())
        assertEquals(
            listOf(FileItem.Folder::class, FileItem.Archive::class, FileItem.Text::class, FileItem.Image::class),
            rows.map { it.item::class },
        )
        val shown = listOf(file(".hidden.zip"), dir(".git")).toRows("", byName.copy(showDotFiles = true))
        assertEquals(setOf(".git", ".hidden.zip"), shown.names().toSet())
    }

    @Test
    fun `names sort in natural order with folders first unless that is off`() {
        val entries = listOf(file("第10巻.zip"), file("第2巻.zip"), dir("第3巻の特典"), file("第1巻.zip"))

        assertEquals(listOf("第3巻の特典", "第1巻.zip", "第2巻.zip", "第10巻.zip"), entries.toRows("", byName).names())
        assertEquals(listOf("第1巻.zip", "第2巻.zip", "第3巻の特典", "第10巻.zip"), entries.toRows("", byName.copy(foldersFirst = false)).names())
    }

    @Test
    fun `dates sort newest or oldest first, with undated entries last and ties by name`() {
        val t = Instant.parse("2026-05-01T00:00:00Z")
        val entries = listOf(file("b.zip", t), file("a.zip", t), file("old.zip", t.minusSeconds(60)), file("undated.zip"))

        assertEquals(listOf("a.zip", "b.zip", "old.zip", "undated.zip"), entries.toRows("", byName.copy(sort = LibrarySort.Newest)).names())
        assertEquals(listOf("old.zip", "a.zip", "b.zip", "undated.zip"), entries.toRows("", byName.copy(sort = LibrarySort.Oldest)).names())
    }

    @Test
    fun `going up reveals the entry the way down went through, and nothing from a folder not above`() {
        assertEquals("漫画", childToward("", "漫画/作品/外伝"))
        assertEquals("漫画/作品", childToward("漫画", "漫画/作品/外伝"))
        assertEquals("漫画/作品", childToward("漫画/", "/漫画/作品"))
        assertEquals(null, childToward("漫画/作品", "漫画/作品"))
        assertEquals(null, childToward("漫画/作品", "漫画/別"))
        assertEquals(null, childToward("漫画/作品", "漫画"))
    }

    @Test
    fun `the filter matches part of the name after NFKC and in either case, folders included`() {
        val rows = listOf(file("ＡＢＣ物語.zip"), dir("abcフォルダ"), file("xyz.zip")).toRows("", byName)

        assertEquals(listOf("abcフォルダ", "ＡＢＣ物語.zip"), rows.filtered("Abc").names())
        assertEquals(rows, rows.filtered("  "))
    }

    @Test
    fun `rows take their path in the place, their read state from the progress, and pages from the index`() {
        val place = LocationId(1)
        val reading = book(place, "作品/1巻.zip")
        val read = book(place, "作品/2巻.zip")
        val folderBook = book(place, "作品/画像", container = Container.Folder)
        val indexed = listOf(
            LibraryBook(reading, null, reading(reading, viewStart = 4)),
            LibraryBook(read, null, reading(read, viewStart = 9, state = ReadState.Read)),
            LibraryBook(folderBook, null, null),
        )
        val rows = listOf(file("1巻.zip"), file("2巻.zip"), file("3巻.zip"), dir("画像"), dir("別"), file("表紙.jpg")).toRows("作品", byName).withMarks(indexed, indexed.progressByPath())

        assertEquals(listOf("作品/別", "作品/画像", "作品/1巻.zip", "作品/2巻.zip", "作品/3巻.zip", "作品/表紙.jpg"), rows.map { it.path })
        assertEquals(
            listOf(null, ReadMark.Unread, ReadMark.Reading(50), ReadMark.Read, ReadMark.Unread, null),
            rows.map { it.mark },
        )
        assertEquals(10, rows.first { it.path == "作品/1巻.zip" }.pageCount)
    }
}
