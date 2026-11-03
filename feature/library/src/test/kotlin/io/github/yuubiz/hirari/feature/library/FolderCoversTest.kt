package io.github.yuubiz.hirari.feature.library

import androidx.lifecycle.ViewModelStore
import io.github.yuubiz.hirari.core.data.FolderCover
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbSubject
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ThumbSize
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.locationBookId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the file list asks of the covers (docs/spec.md sections 4.3 and 4.4). */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderCoversTest {
    private val fixture = Fixture()
    private val place = LibraryLocation.SafTree(LocationId(1), "漫画", UriString("content://tree/manga"))
    private val at = Instant.parse("2026-02-03T04:05:06Z")

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun entry(name: String, directory: Boolean = false) = FolderEntry(name, "doc:$name", directory, size = if (directory) null else 100, modified = at)

    private fun TestScope.viewModel(path: String = "作品") =
        FolderViewModel(CompletableDeferred(fixture.services), FolderKey(place.id, path, "doc:$path"), UnconfinedTestDispatcher(testScheduler))
            .also { watch(it.state) }

    private fun FolderViewModel.shown() = assertIs<FolderContent.Shown>(state.value.content)

    @Test
    fun `every cover row gets the spec of its thumbnail and a text gets none`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("1巻.zip"), entry("p.png"), entry("外伝", directory = true), entry("後書き.txt"))
        val rows = viewModel().shown().rows.associateBy { it.item.entry.name }

        val archive = assertIs<CoverSpec.Book>(rows.getValue("1巻.zip").cover)
        assertEquals(locationBookId(place, "作品/1巻.zip"), archive.id)
        assertEquals(CoverOrigin.Archive(place, "作品/1巻.zip", "doc:1巻.zip"), archive.origin)
        assertEquals(at, archive.modified)
        assertEquals(100, archive.byteSize)
        assertNull(archive.coverEntry)
        assertEquals(CoverOrigin.Image(place, "作品/p.png", "doc:p.png"), assertIs<CoverSpec.Book>(rows.getValue("p.png").cover).origin)
        val folder = assertIs<CoverSpec.Folder>(rows.getValue("外伝").cover)
        assertEquals("作品/外伝", folder.path)
        assertNull(folder.chosen)
        assertNull(rows.getValue("後書き.txt").cover)
    }

    @Test
    fun `the direction of a book is its own and the others follow the global one`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("1巻.zip"), entry("2巻.zip"))
        fixture.settings.update { it.copy(readingDirection = ReadingDirection.RightToLeft) }
        val list = viewModel()
        fun directions() = list.shown().rows.map { (it.cover as CoverSpec.Book).direction }
        assertEquals(listOf(ReadingDirection.RightToLeft, ReadingDirection.RightToLeft), directions())

        fixture.coverIndex.directions.value = mapOf(locationBookId(place, "作品/2巻.zip") to ReadingDirection.LeftToRight)
        assertEquals(listOf(ReadingDirection.RightToLeft, ReadingDirection.LeftToRight), directions())

        fixture.settings.update { it.copy(readingDirection = ReadingDirection.LeftToRight) }
        assertEquals(listOf(ReadingDirection.LeftToRight, ReadingDirection.LeftToRight), directions())
        assertEquals(1, fixture.lister.calls)
    }

    @Test
    fun `the page the user chose as the cover of an indexed book is part of its spec`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("1巻.zip"))
        val indexed = book(place.id, "作品/1巻.zip").copy(id = locationBookId(place, "作品/1巻.zip"))
        fixture.books.put(indexed)
        val list = viewModel()
        assertNull((list.shown().rows.single().cover as CoverSpec.Book).coverEntry)

        fixture.books.chosenPages.value = mapOf(indexed.id to 7)
        assertEquals(7, (list.shown().rows.single().cover as CoverSpec.Book).coverEntry)
    }

    @Test
    fun `a mark changing leaves the cover of the row as it was`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("1巻.zip"))
        val list = viewModel()
        val before = list.shown().rows.single().cover

        list.mark(list.shown().rows.single(), ReadState.Read)
        assertEquals(before, list.shown().rows.single().cover)
    }

    @Test
    fun `the image chosen for a folder is found once in its folder and the rows show before it is`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("外伝", directory = true))
        fixture.coverBooks.folders["作品/外伝"] = listOf(entry("cover.jpg"), entry("1.zip"))
        fixture.coverIndex.folderCovers.value = mapOf("作品/外伝" to FolderCover("作品/外伝/cover.jpg"), "別" to FolderCover("別/x.jpg"))
        val list = viewModel()

        val chosen = assertIs<CoverSpec.Folder>(list.shown().rows.single().cover).chosen
        assertEquals(CoverOrigin.Image(place, "作品/外伝/cover.jpg", "doc:cover.jpg"), chosen?.origin)
        assertEquals(listOf("作品/外伝"), fixture.coverBooks.listCalls)

        fixture.coverIndex.folderCovers.value = mapOf("作品/外伝" to FolderCover("作品/外伝/1.zip", entry = 3))
        val page = assertIs<CoverSpec.Folder>(list.shown().rows.single().cover).chosen
        assertEquals(3, page?.coverEntry)
        assertEquals(CoverOrigin.Archive(place, "作品/外伝/1.zip", "doc:1.zip"), page?.origin)

        fixture.coverIndex.folderCovers.value = emptyMap()
        assertNull(assertIs<CoverSpec.Folder>(list.shown().rows.single().cover).chosen)
    }

    @Test
    fun `a folder with a chosen image shows no cover until the image is found, and the others do at once`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("外伝", directory = true), entry("番外", directory = true), entry("1.zip"))
        fixture.coverBooks.folders["作品/外伝"] = listOf(entry("cover.jpg"))
        fixture.coverIndex.folderCovers.value = mapOf("作品/外伝" to FolderCover("作品/外伝/cover.jpg"))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fixture.coverBooks.gate = gate
        val list = viewModel()

        val before = list.shown().rows.associate { it.item.entry.name to it.cover }
        assertNull(before.getValue("外伝"))
        assertIs<CoverSpec.Folder>(before.getValue("番外"))
        assertIs<CoverSpec.Book>(before.getValue("1.zip"))

        gate.complete(Unit)
        assertEquals("作品/外伝/cover.jpg", (assertIs<CoverSpec.Folder>(list.shown().rows.first { it.item.entry.name == "外伝" }.cover).chosen?.origin as CoverOrigin.Image).path)
    }

    @Test
    fun `a folder shows its chosen cover once it is found, while another folder is still looked up`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("外伝", directory = true), entry("番外", directory = true))
        fixture.coverBooks.folders["作品/外伝"] = listOf(entry("a.jpg"))
        fixture.coverBooks.folders["作品/番外"] = listOf(entry("b.jpg"))
        fixture.coverIndex.folderCovers.value = mapOf("作品/番外" to FolderCover("作品/番外/b.jpg"), "作品/外伝" to FolderCover("作品/外伝/a.jpg"))
        val slow = CompletableDeferred<Unit>()
        fixture.coverBooks.stalls["作品/番外"] = slow
        val list = viewModel()
        fun cover(name: String) = list.shown().rows.single { it.item.entry.name == name }.cover as CoverSpec.Folder?

        assertEquals(locationBookId(place, "作品/外伝/a.jpg"), cover("外伝")?.chosen?.id)
        assertNull(cover("番外"))
        slow.complete(Unit)
        assertEquals(locationBookId(place, "作品/番外/b.jpg"), cover("番外")?.chosen?.id)
    }

    @Test
    fun `a chosen image that cannot be found leaves the folder searched`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("外伝", directory = true))
        fixture.coverIndex.folderCovers.value = mapOf("作品/外伝" to FolderCover("作品/外伝/gone.jpg"))
        assertNull(assertIs<CoverSpec.Folder>(viewModel().shown().rows.single().cover).chosen)
    }

    @Test
    fun `making a thumbnail again remakes the row at every size, the shown one first`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("1巻.zip"))
        fixture.settings.update { it.copy(thumbSize = ThumbSize.Large) }
        val list = viewModel()

        list.remake(list.shown().rows.single())
        assertEquals(listOf(412, 252, 320), fixture.thumbnails.regenerated.map { it.sizePx })
        assertEquals(setOf(locationBookId(place, "作品/1巻.zip")), fixture.thumbnails.regenerated.map { (it.subject as ThumbSubject.Book).id }.toSet())
    }

    @Test
    fun `a text has nothing to make again`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("後書き.txt"))
        val list = viewModel()
        list.remake(list.shown().rows.single())
        assertTrue(fixture.thumbnails.regenerated.isEmpty())
    }

    @Test
    fun `choosing an image as the cover of the folder saves it and remakes the folder, which its parent dates`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("p.png"))
        fixture.coverBooks.folders[""] = listOf(entry("作品", directory = true))
        fixture.coverBooks.folders["作品"] = listOf(entry("p.png"))
        val list = viewModel()
        val row = list.shown().rows.single()

        list.chooseFolderCover(row, choose = true)
        assertEquals(listOf(Triple<LocationId, String, FolderCover?>(place.id, "作品", FolderCover("作品/p.png"))), fixture.locations.chosenCovers)
        assertEquals(listOf(320, 252, 412), fixture.thumbnails.regenerated.map { it.sizePx })
        val folder = fixture.thumbnails.regenerated.map { it.subject }.first()
        assertIs<ThumbSubject.Folder>(folder)
        assertEquals(locationBookId(place, "作品"), folder.id)
        assertEquals(locationBookId(place, "作品/p.png"), folder.chosen?.id)
        assertEquals(at, folder.modified)
    }

    @Test
    fun `a folder cover chosen just before leaving the screen is still remade, and the rows showing the folder look again`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("p.png"))
        fixture.coverBooks.folders[""] = listOf(entry("作品", directory = true))
        fixture.coverBooks.folders["作品"] = listOf(entry("p.png"))
        val screen = ViewModelStore()
        val list = screen.own { FolderViewModel(CompletableDeferred(fixture.services), FolderKey(place.id, "作品", "doc:作品"), UnconfinedTestDispatcher(testScheduler)) }
        watch(list.state)
        val folder = checkNotNull(fixture.covers.folderSpec(place, "作品", fixture.settings.value.value.readingDirection, null))
        watch(fixture.covers.show(CoverRef(folder, 320)))
        fun folderLooks() = fixture.thumbnails.watches.count { it.request.subject.id == folder.id }
        assertEquals(1, folderLooks())
        val slow = CompletableDeferred<Unit>()
        fixture.coverBooks.gate = slow

        list.chooseFolderCover(list.shown().rows.single(), choose = true)
        screen.clear()
        slow.complete(Unit)
        assertEquals(listOf(320, 252, 412), fixture.thumbnails.regenerated.map { it.sizePx })
        assertEquals(setOf(locationBookId(place, "作品/p.png")), fixture.thumbnails.regenerated.map { (it.subject as ThumbSubject.Folder).chosen?.id }.toSet())
        assertEquals(2, folderLooks())
    }

    @Test
    fun `a folder cover that cannot be saved does not end the app`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("p.png"))
        fixture.locations.folderCoverFailure = java.io.IOException("disk full")
        val list = FolderViewModel(CompletableDeferred(fixture.services), FolderKey(place.id, "作品", "doc:作品"), UnconfinedTestDispatcher(testScheduler))
        watch(list.state)

        list.chooseFolderCover(list.shown().rows.single(), choose = true)
        assertEquals(emptyList(), fixture.uncaught)
    }

    @Test
    fun `choosing another folder cover while the first choice is still looked up ends on the second`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("a.png"), entry("b.png"))
        fixture.coverBooks.folders[""] = listOf(entry("作品", directory = true))
        fixture.coverBooks.folders["作品"] = listOf(entry("a.png"), entry("b.png"))
        val list = viewModel()
        val rows = list.shown().rows.associateBy { it.item.entry.name }
        val slow = CompletableDeferred<Unit>()
        fixture.coverBooks.stalls["作品"] = slow

        list.chooseFolderCover(rows.getValue("a.png"), choose = true)
        list.chooseFolderCover(rows.getValue("b.png"), choose = true)
        slow.complete(Unit)
        assertEquals(FolderCover("作品/b.png"), fixture.locations.chosenCovers.last().third)
        val b = locationBookId(place, "作品/b.png")
        assertEquals(listOf(b, b, b), fixture.thumbnails.regenerated.map { (it.subject as ThumbSubject.Folder).chosen?.id })
    }

    @Test
    fun `giving up the cover of the folder clears the choice and remakes the folder`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("p.png"))
        fixture.coverBooks.folders[""] = listOf(entry("作品", directory = true))
        fixture.coverIndex.folderCovers.value = mapOf("作品" to FolderCover("作品/p.png"))
        fixture.coverBooks.folders["作品"] = listOf(entry("p.png"))
        val list = viewModel()
        assertEquals("作品/p.png", list.shown().folderCoverPath)

        list.chooseFolderCover(list.shown().rows.single(), choose = false)
        assertEquals(listOf(Triple<LocationId, String, FolderCover?>(place.id, "作品", null)), fixture.locations.chosenCovers)
        val folder = fixture.thumbnails.regenerated.first().subject
        assertNull(assertIs<ThumbSubject.Folder>(folder).chosen)
    }
}
