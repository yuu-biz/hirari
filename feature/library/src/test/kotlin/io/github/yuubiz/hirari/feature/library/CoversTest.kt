package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.FolderCover
import io.github.yuubiz.hirari.core.image.thumbnail.FolderEntry as ThumbEntry
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbLane
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbPriority
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbSubject
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ThumbSize
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.locationBookId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoversTest {
    private val fixture = Fixture()
    private val covers = fixture.covers
    private val saf = LibraryLocation.SafTree(LocationId(1), "漫画", UriString("content://tree/manga"))
    private val nas = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "", null, null, null)
    private val at = Instant.parse("2026-02-03T04:05:06Z")

    private fun entry(name: String, directory: Boolean = false, size: Long? = 100, id: String? = "doc:$name") =
        FolderEntry(name, id, directory, size, at)

    private fun book(place: LibraryLocation, path: String, cover: Int? = null, direction: ReadingDirection = ReadingDirection.RightToLeft) =
        CoverSpec.Book(locationBookId(place, path), at, 100, cover, direction, CoverOrigin.Archive(place, path, null))

    @Test
    fun `a request names the subject, the size, the direction and the priority`() {
        val spec = book(saf, "s/1.zip", cover = 4, direction = ReadingDirection.LeftToRight)
        val request = covers.request(CoverRef(spec, 320), ThumbPriority.Nearby)
        val subject = assertIs<ThumbSubject.Book>(request.subject)
        assertEquals(spec.id, subject.id)
        assertEquals(4, subject.coverEntry)
        assertEquals(100, subject.byteSize)
        assertEquals(ThumbLane.Local, subject.lane)
        assertEquals(320, request.sizePx)
        assertEquals(ReadingDirection.LeftToRight, request.direction)
        assertEquals(ThumbPriority.Nearby, request.priority)
    }

    @Test
    fun `a range of the cover page goes to the subject and makes another key than the page whole`() {
        val crop = CoverCrop(1000, 2000, 6000, 9000)
        val whole = book(saf, "s/1.zip", cover = 4)
        val ranged = whole.copy(coverCrop = crop)
        val subject = assertIs<ThumbSubject.Book>(covers.request(CoverRef(ranged, 320), ThumbPriority.Visible).subject)
        assertEquals(crop, subject.coverCrop)
        assertNull(assertIs<ThumbSubject.Book>(covers.request(CoverRef(whole, 320), ThumbPriority.Visible).subject).coverCrop)
        assertNotEquals(whole.key(320), ranged.key(320))
        assertEquals(whole.key(320), whole.copy(coverCrop = null).key(320))
        assertNotEquals(ranged.key(320), ranged.copy(coverCrop = CoverCrop(1000, 2000, 6000, 9001)).key(320))
    }

    @Test
    fun `a book of an SMB place is made in the SMB lane`() {
        val request = covers.request(CoverRef(book(nas, "s/1.zip"), 320), ThumbPriority.Visible)
        assertEquals(ThumbLane.Smb, assertIs<ThumbSubject.Book>(request.subject).lane)
    }

    @Test
    fun `the same cover at the same size is the same key, whatever its priority or chosen image`() {
        val chosen = book(saf, "f/cover.jpg")
        val folder = CoverSpec.Folder(locationBookId(saf, "f"), at, ReadingDirection.RightToLeft, saf, "f", "doc:f", null)
        assertEquals(folder.key(320), folder.copy(chosen = chosen).key(320))
        assertTrue(folder.key(320).folder)
        assertFalse(book(saf, "f").key(320).folder)
        assertNotEquals(folder.key(320), folder.key(321))
    }

    @Test
    fun `a folder lists what a cover can come from, leaving out hidden files and texts and descending into folders`() = runTest {
        fixture.coverBooks.folders["f"] = listOf(
            entry("b.zip"),
            entry(".hidden.zip"),
            entry("notes.txt"),
            entry("pic.png"),
            entry("sub", directory = true),
            entry("readme.md"),
        )
        fixture.coverBooks.folders["f/sub"] = listOf(entry("c.pdf"))
        val chosen = book(saf, "f/cover.jpg")
        val spec = CoverSpec.Folder(locationBookId(saf, "f"), at, ReadingDirection.LeftToRight, saf, "f", "doc:f", chosen)
        val folder = assertIs<ThumbSubject.Folder>(covers.request(CoverRef(spec, 320), ThumbPriority.Visible).subject)

        assertEquals(chosen.id, folder.chosen?.id)
        val children = folder.children()
        assertEquals(listOf("b.zip", "pic.png", "sub"), children.map { it.name })
        val archive = assertIs<ThumbEntry.Item>(children[0]).book
        assertEquals(locationBookId(saf, "f/b.zip"), archive.id)
        assertEquals(at, archive.modified)
        val inside = assertIs<ThumbEntry.Directory>(children[2]).children()
        assertEquals(listOf("c.pdf"), inside.map { it.name })
        assertEquals(locationBookId(saf, "f/sub/c.pdf"), assertIs<ThumbEntry.Item>(inside.single()).book.id)
        assertEquals(listOf("f", "f/sub"), fixture.coverBooks.listCalls)
    }

    @Test
    fun `a folder of an SMB place searches its books in the SMB lane`() = runTest {
        fixture.coverBooks.folders["f"] = listOf(entry("b.zip", id = null))
        val spec = CoverSpec.Folder(locationBookId(nas, "f"), at, ReadingDirection.RightToLeft, nas, "f", null, null)
        val folder = assertIs<ThumbSubject.Folder>(covers.request(CoverRef(spec, 320), ThumbPriority.Visible).subject)
        assertEquals(ThumbLane.Smb, assertIs<ThumbEntry.Item>(folder.children().single()).book.lane)
    }

    @Test
    fun `sizes follow the cell of the thumbnail size and the density`() {
        assertEquals(252, covers.sizePx(ThumbSize.Small))
        assertEquals(320, covers.sizePx(ThumbSize.Medium))
        assertEquals(412, covers.sizePx(ThumbSize.Large))
        assertEquals(listOf(412, 252, 320), covers.sizesFrom(ThumbSize.Large))
    }

    @Test
    fun `regenerating asks for each size in turn with the visible priority`() = runTest {
        covers.regenerate(book(saf, "s/1.zip"), covers.sizesFrom(ThumbSize.Medium))
        assertEquals(listOf(320, 252, 412), fixture.thumbnails.regenerated.map { it.sizePx })
        assertEquals(setOf(ThumbPriority.Visible), fixture.thumbnails.regenerated.map { it.priority }.toSet())
    }

    @Test
    fun `an entry is found in its folder by its normalized name, and a folder that cannot be listed finds nothing`() = runTest {
        fixture.coverBooks.folders["f"] = listOf(entry("が.jpg"))
        val found = covers.find(saf, "f/が.jpg")
        assertEquals("が.jpg", found?.name)
        assertNull(covers.find(saf, "g/x.jpg"))
        assertNull(covers.find(saf, "f/other.jpg"))
    }

    @Test
    fun `chosen images are looked up a few at once in the order given, and each folder leaves pending as its own lookup ends`() = runTest {
        val paths = (1..6).map { "f$it" }
        val slow = paths.associateWith { CompletableDeferred<Unit>() }
        for (path in paths) fixture.coverBooks.folders[path] = listOf(entry("c.jpg"))
        fixture.coverBooks.stalls.putAll(slow)
        val seen = mutableListOf<ChosenCovers>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            covers.chosenCovers(saf, paths.associateWith { FolderCover("$it/c.jpg") }).collect { seen += it }
        }
        assertEquals(ChosenCovers(emptyMap(), paths.toSet()), seen.single())
        assertEquals(listOf("f1", "f2", "f3", "f4"), fixture.coverBooks.listCalls)

        slow.getValue("f3").complete(Unit)
        assertEquals(setOf("f3"), seen.last().found.keys)
        assertEquals(paths.toSet() - "f3", seen.last().pending)
        assertEquals(listOf("f1", "f2", "f3", "f4", "f5"), fixture.coverBooks.listCalls)
    }

    @Test
    fun `a folder spec takes the date of the folder's own entry in its parent`() = runTest {
        fixture.coverBooks.folders[""] = listOf(entry("f", directory = true, size = null).copy(modified = at))
        val spec = covers.folderSpec(saf, "f", ReadingDirection.RightToLeft, ChosenCover("f/p.png", null, entry("p.png")))
        assertEquals(at, spec?.modified)
        assertEquals("doc:f", spec?.documentId)
        assertEquals(locationBookId(saf, "f/p.png"), spec?.chosen?.id)
        assertNull(covers.folderSpec(saf, "missing", ReadingDirection.RightToLeft, null))
    }
}
