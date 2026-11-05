package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.LibrarySection
import io.github.yuubiz.hirari.core.model.LibrarySort
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileGroupsTest {
    @Test
    fun `metadata alone rebuilds both levels and their original filters before applying current settings`() {
        val source = rows("[Z (B)] Story 1.zip", "[B] Story 2.zip", "[B] Story 3.zip",
            "[B] Tale 1.zip", "[B] Tale 2.zip", "[C] Story 1.zip", "[C] Story 2.zip")
        val author = group(source).first { it.pseudo!!.name != "C" }.pseudo!!
        val authorMetadata = author.copy(memberKeys = emptyList())
        val authorRows = resolvePseudoFolder(source, authorMetadata, ListLook(grouping = LibraryGrouping.Author)).second
        val story = pseudoFolders(authorRows.filtered("Story"), LibraryGrouping.Series, true, "Story", LibrarySort.Name, true, author)
            .single().pseudo!!
        val metadata = story.copy(memberKeys = emptyList(), parent = authorMetadata)
        val look = ListLook(grouping = LibraryGrouping.Author, subgrouping = LibraryGrouping.Series, mergeCircleAuthors = false)
        val snapshot = rememberPseudoFolder(source, metadata)!!
        assertEquals(author.memberKeys, snapshot.parent!!.memberKeys)
        assertEquals(story.memberKeys, snapshot.memberKeys)
        val (_, members) = resolvePseudoFolder(source, snapshot, look)
        assertEquals(listOf("[B] Story 2.zip", "[B] Story 3.zip"), members.map { it.item.entry.name })
        assertEquals(members, resolvePseudoFolder(source, metadata, look).second)
        val withoutStory = source.filterNot { "Story" in it.item.entry.name }
        assertEquals(group(withoutStory).first { it.pseudo!!.name != "C" }.pseudo, resolvePseudoFolder(withoutStory, metadata, look.copy(mergeCircleAuthors = true)).first)
        assertEquals(null, resolvePseudoFolder(source, metadata, look.copy(grouping = LibraryGrouping.None)).first)
    }

    @Test
    fun `metadata recognizes a merged author whose canonical key changed before opening`() {
        val original = rows("[Z (B)] one.zip", "[B] two.zip")
        val metadata = group(original).single().pseudo!!.copy(memberKeys = emptyList())
        val current = original + rows("[A (B)] three.zip")
        val look = ListLook(grouping = LibraryGrouping.Author)
        assertEquals(current.toSet(), resolvePseudoFolder(current, metadata, look).second.toSet())
        val snapshot = rememberPseudoFolder(original, metadata)!!
        assertEquals(2, snapshot.memberKeys.size)
        assertEquals(current.toSet(), resolvePseudoFolder(current, snapshot, look).second.toSet())
    }

    @Test
    fun `ViewModel snapshot follows remaining members after the key defining link disappears`() {
        val original = rows("[Z (B)] link.zip", "[Z] one.zip", "[Z] two.zip")
        val metadata = group(original).single().pseudo!!.copy(memberKeys = emptyList())
        val snapshot = rememberPseudoFolder(original, metadata)!!
        val remaining = original.filterNot { "link" in it.item.entry.name }
        val look = ListLook(grouping = LibraryGrouping.Author)
        assertEquals(group(remaining).single().pseudo, resolvePseudoFolder(remaining, snapshot, look).first)
        assertEquals(remaining, resolvePseudoFolder(remaining, snapshot, look).second)
        assertEquals(null, resolvePseudoFolder(remaining, metadata, look).first)
    }

    @Test
    fun `membership picks the largest surviving part after a merged group splits and falls back when all disappear`() {
        val source = rows("[Z (B)] one.zip", "[B] two.zip", "[C (B)] four.zip", "[C] five.zip", "[C] six.zip", "[C] seven.zip")
        val opened = group(source).single().pseudo!!
        val split = source + rows("[B] new.zip")
        val look = ListLook(grouping = LibraryGrouping.Author, mergeCircleAuthors = false)
        assertEquals(listOf("[C] five.zip", "[C] seven.zip", "[C] six.zip"), resolvePseudoFolder(split, opened, look).second.map { it.item.entry.name })
        assertEquals(null, resolvePseudoFolder(rows("[D] 1.zip", "[D] 2.zip"), opened, look).first)
    }

    @Test
    fun `author then series leaves singles and missing keys and stops after two levels`() {
        val place = io.github.yuubiz.hirari.core.model.LibraryLocation.SafTree(io.github.yuubiz.hirari.core.model.LocationId(1), "Books",
            io.github.yuubiz.hirari.core.model.UriString("content://tree/books"))
        val source = rows("[A] Story 1.zip", "[A] Story 2.zip", "[A] Tale 1.zip", "[A] Tale 2.zip",
            "[A] Solo 1.zip", "[A].zip", "[B] Story 1.zip", "[B] Story 2.zip")
            .withCovers(emptyList(), CoverContext(place, io.github.yuubiz.hirari.core.model.ReadingDirection.RightToLeft, emptyMap(), emptyMap()))
        val look = ListLook(grouping = LibraryGrouping.Author, subgrouping = LibraryGrouping.Series)
        val author = group(source).first { it.pseudo?.name == "A" }.pseudo!!
        val (_, members) = resolvePseudoFolder(source, author, look)
        val second = pseudoFolders(members, look.groupingInside(author), true, "", LibrarySort.Name, true, author)
        assertEquals(listOf("Story", "Tale"), second.pseudoRows().map { it.pseudo!!.name })
        assertEquals(setOf("[A].zip", "[A] Solo 1.zip"), second.filter { it.pseudo == null }.map { it.item.entry.name }.toSet())
        val story = second.first().pseudo!!
        assertEquals(author, story.parent)
        assertTrue(story.key.contains(author.key))
        val (resolved, books) = resolvePseudoFolder(source, story, look)
        assertEquals(story, resolved)
        assertEquals(listOf("[A] Story 1.zip", "[A] Story 2.zip"), books.map { it.item.entry.name })
        assertSame(members.first { it.item.entry.name == "[A] Story 1.zip" }.cover, second.first().cover)
        assertEquals(LibraryGrouping.None, look.groupingInside(story))
        assertEquals(books, pseudoFolders(books, LibraryGrouping.Author, true, "", LibrarySort.Name, true, story))
        val otherAuthor = group(source).first { it.pseudo?.name == "B" }.pseudo!!
        val otherMembers = resolvePseudoFolder(source, otherAuthor, look).second
        val otherStory = pseudoFolders(otherMembers, LibraryGrouping.Series, true, "", LibrarySort.Name, true, otherAuthor).single().pseudo!!
        assertTrue(story.key != otherStory.key)
        val filtered = pseudoFolders(members.filtered("Story 1"), LibraryGrouping.Series, true, "Story 1", LibrarySort.Name, true, author)
        assertEquals(listOf("[A] Story 1.zip"), filtered.map { it.item.entry.name })
        assertTrue(filtered.all { it.pseudo == null })
    }

    @Test
    fun `equal second key and disabled first key both suppress nested grouping`() {
        val source = rows("[A] Story 1.zip", "[A] Story 2.zip")
        val author = group(source).single().pseudo!!
        val equal = ListLook(grouping = LibraryGrouping.Author, subgrouping = LibraryGrouping.Author)
        assertEquals(LibraryGrouping.None, equal.effectiveSubgrouping)
        assertEquals(LibraryGrouping.None, equal.groupingInside(author))
        assertEquals(source, pseudoFolders(source, equal.groupingInside(author), true, "", LibrarySort.Name, true, author))
        assertEquals(LibraryGrouping.None, ListLook(subgrouping = LibraryGrouping.Series).effectiveSubgrouping)
    }

    private fun entry(name: String, folder: Boolean = false, date: String? = null) =
        FolderEntry(name, name, folder, 1, date?.let(Instant::parse))
    private fun rows(vararg names: String) = names.map { entry(it) }.toRows("folder", Ordering(LibrarySort.Name, false, false))
    private fun group(rows: List<FileRow>, kind: LibraryGrouping = LibraryGrouping.Author, merge: Boolean = true,
        sort: LibrarySort = LibrarySort.Name, first: Boolean = true) = pseudoFolders(rows, kind, merge, "", sort, first)
    private fun List<FileRow>.pseudoRows() = filter { it.pseudo != null }
    private fun List<ListCell>.headers() = filterIsInstance<ListCell.Header>()

    @Test
    fun `only multi item file groups become folders and singletons and missing keys stay`() {
        val input = (listOf(entry("[A] real folder", true)) + listOf("[A] 1.zip", "[A] 2.txt", "[B] alone.jpg", "plain.zip").map { entry(it) })
            .toRows("folder", Ordering(LibrarySort.Name, true, false))
        val result = group(input)
        assertEquals(4, result.size)
        assertSame(input.first(), result.first { it.pseudo == null && it.item is FileItem.Folder })
        assertEquals(listOf("[B] alone.jpg", "plain.zip"), result.filter { it.item !is FileItem.Folder }.map { it.item.entry.name })
        val pseudo = result.pseudoRows().single()
        assertEquals("A", pseudo.pseudo!!.name)
        assertEquals("2 件", pseudo.sizeText)
        assertEquals(2, fileGroups(input, LibraryGrouping.Author, true).first { it.name == "A" }.rows.size)
        assertEquals(input, group(input, LibraryGrouping.None))
        assertTrue(group(rows("[] 1.zip", "[] 2.zip")).pseudoRows().isEmpty())
    }

    @Test
    fun `filtering first removes singleton folders and hidden creator links`() {
        val input = rows("[Circle] keep.zip", "[Author] keep.zip", "[Circle (Author)] remove.zip")
        assertEquals(1, group(input).pseudoRows().size)
        assertEquals(input.filtered("keep"), group(input.filtered("keep")))
        val same = rows("[A] keep1.zip", "[A] keep2.zip", "[A] remove.zip")
        assertEquals("2 件", group(same.filtered("keep")).single().sizeText)
        assertEquals(same.filtered("keep1"), group(same.filtered("keep1")))
    }

    @Test
    fun `cover is the first sorted member exact cover spec and members keep date order`() {
        val input = listOf(entry("[A] old.zip", date = "2026-01-01T00:00:00Z"), entry("[A] new.zip", date = "2026-11-01T00:00:00Z"))
            .toRows("folder", Ordering(LibrarySort.Newest, true, false))
        val place = io.github.yuubiz.hirari.core.model.LibraryLocation.SafTree(io.github.yuubiz.hirari.core.model.LocationId(1), "books",
            io.github.yuubiz.hirari.core.model.UriString("content://tree/books"))
        val covered = input.withCovers(emptyList(), CoverContext(place, io.github.yuubiz.hirari.core.model.ReadingDirection.RightToLeft, emptyMap(), emptyMap()))
        val pseudo = group(covered, sort = LibrarySort.Newest).single()
        assertSame(covered.first().cover, pseudo.cover)
        assertEquals(covered, fileGroups(covered, LibraryGrouping.Author, true).single().rows)
    }

    @Test
    fun `pseudo folders and real folders share natural name order and obey folders first`() {
        val input = (listOf(entry("B folder", true), entry("A folder", true)) + listOf("[C10] 1.zip", "[C10] 2.zip", "[C2] 1.zip", "[C2] 2.zip", "0.zip").map { entry(it) })
            .toRows("folder", Ordering(LibrarySort.Name, true, false))
        assertEquals(listOf("A folder", "B folder", "C2", "C10", "0.zip"), group(input).map { it.item.entry.name })
        assertEquals(listOf("0.zip", "A folder", "B folder", "C2", "C10"), group(input, first = false).map { it.item.entry.name })
        for (sort in listOf(LibrarySort.Newest, LibrarySort.Oldest)) {
            assertEquals(listOf("A folder", "B folder", "C2", "C10"), group(input, sort = sort).take(4).map { it.item.entry.name })
        }
    }

    @Test
    fun `author normalization merge keys and preferred spellings stay compatible`() {
        val input = rows("[Circle1 (Author)] 1.zip", "［Ｃｉｒｃｌｅ２ （ＡＵＴＨＯＲ）］ 2.zip", "[circle2 (Other)] 3.zip", "[other] 4.zip", "[ CIRCLE1 ] 5.zip")
        val merged = fileGroups(input, LibraryGrouping.Author, true).single()
        assertEquals(5, merged.rows.size)
        assertEquals("/group:Author:author", merged.key)
        assertEquals(5, fileGroups(input, LibraryGrouping.Author, false).size)
        assertEquals(1, group(rows("[Straße] 1.zip", "[STRASSE] 2.zip")).size)
        assertEquals(1, group(rows("[Σ] 1.zip", "[ς] 2.zip")).size)
        val preferred = group(rows("[Author] 1.zip", "[Author] 2.zip", "[Circle (Author)] 3.zip")).single()
        assertEquals("Circle (Author)", preferred.pseudo!!.name)
        assertEquals(merged.key, fileGroups(input.reversed(), LibraryGrouping.Author, true).single().key)
    }

    @Test
    fun `a real folder cannot bridge author components`() {
        val input = listOf(entry("[Circle] one.zip"), entry("[Author] two.zip"), entry("[Circle (Author)] bridge", true))
            .toRows("", Ordering(LibrarySort.Name, true, false))
        assertTrue(group(input).pseudoRows().isEmpty())
    }

    @Test
    fun `series original and event use first spelling and normalized keys`() {
        val input = rows("(C108) [A] Story vol.1 (オリジナル) [DL版].zip", "（ｃ１０８）［B］Ｓｔｏｒｙ 第２巻（オリジナル）［タグ］.zip")
        for ((kind, name) in listOf(LibraryGrouping.Series to "Story", LibraryGrouping.Original to "オリジナル", LibraryGrouping.Event to "C108")) {
            assertEquals(name, group(input, kind).single().pseudo!!.name)
        }
        assertEquals(rows("plain.zip", "other.zip"), group(rows("plain.zip", "other.zip"), LibraryGrouping.Event))
    }

    @Test
    fun `read sections order folders first omit empty groups and fold by stable keys`() {
        val source = rows("read.zip", "reading.zip", "unread.zip", "image.jpg").map {
            it.copy(mark = when (it.item.entry.name) { "read.zip" -> ReadMark.Read; "reading.zip" -> ReadMark.Reading(20); "unread.zip" -> ReadMark.Unread; else -> null })
        }
        val folders = listOf(entry("real", true)).toRows("", Ordering(LibrarySort.Name, true, false)) + group(rows("[A] 1.zip", "[A] 2.zip"))
        val cells = fileCells(source + folders, LibrarySection.ReadState, true)
        assertIs<ListCell.Parent>(cells.first())
        assertEquals(listOf("フォルダ", "未読", "読みかけ", "読了"), cells.headers().map { it.name })
        assertEquals(listOf(2, 2, 1, 1), cells.headers().map { it.count })
        val folded = fileCells(source + folders, LibrarySection.ReadState, true, setOf(cells.headers().first().key))
        assertEquals(cells.headers(), folded.headers())
        assertEquals(source.size, folded.filterIsInstance<ListCell.Entry>().size)
        assertEquals(listOf("読了"), fileCells(source.filter { it.mark == ReadMark.Read }, LibrarySection.ReadState, false).headers().map { it.name })
        assertEquals(listOf(ListCell.Parent), fileCells(emptyList(), LibrarySection.ReadState, true))
        assertEquals(source.map { ListCell.Entry(it) }, fileCells(source, LibrarySection.None, false))
    }

    @Test
    fun `time sections use local dates newest buckets first unknown last and folders first`() {
        val dates = listOf(null, "2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z", "2026-11-03T00:00:00Z", "2026-11-04T15:01:00Z")
        val source = dates.mapIndexed { i, date -> entry("$i.zip", date = date) }.toRows("", Ordering(LibrarySort.Name, false, false))
        val folders = listOf(entry("real", true)).toRows("", Ordering(LibrarySort.Name, true, false)) + group(rows("[A] 1.zip", "[A] 2.zip"))
        val cells = fileCells(source + folders, LibrarySection.AddedTime, false, now = Instant.parse("2026-11-04T16:00:00Z"), zone = ZoneId.of("Asia/Tokyo"))
        // Tokyo is already November 5; November 3 is this week, so add November 4 for yesterday separately.
        assertEquals(listOf("フォルダ", "今日", "今週", "今月", "今年", "それ以前", "日時不明"), cells.headers().map { it.name })
        assertEquals(2, cells.headers().first().count)
        assertEquals("0.zip", (cells.last() as ListCell.Entry).row.item.entry.name)
        val yesterday = listOf(entry("y.zip", date = "2026-11-04T00:00:00Z")).toRows("", Ordering(LibrarySort.Name, false, false))
        assertEquals("昨日", fileCells(yesterday, LibrarySection.AddedTime, false, now = Instant.parse("2026-11-04T16:00:00Z"), zone = ZoneId.of("Asia/Tokyo")).headers().single().name)
    }

    @Test
    fun `week starts Monday including across a month boundary with yesterday taking priority`() {
        val source = listOf(entry("monday.zip", date = "2026-08-31T12:00:00Z"), entry("sunday.zip", date = "2026-08-30T12:00:00Z"))
            .toRows("", Ordering(LibrarySort.Name, false, false))
        val zone = ZoneId.of("UTC")
        val wednesday = fileCells(source, LibrarySection.AddedTime, false, now = Instant.parse("2026-09-02T12:00:00Z"), zone = zone)
        assertEquals(listOf("今週", "今年"), wednesday.headers().map { it.name })
        val monday = fileCells(source, LibrarySection.AddedTime, false, now = Instant.parse("2026-08-31T12:00:00Z"), zone = zone)
        assertEquals(listOf("今日", "昨日"), monday.headers().map { it.name })
        assertEquals(listOf(ListCell.Parent), fileCells(emptyList(), LibrarySection.AddedTime, true))
    }
}
