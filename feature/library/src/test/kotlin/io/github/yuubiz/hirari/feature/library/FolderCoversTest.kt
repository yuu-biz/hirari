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
    fun `chosen folder covers stay unresolved until requested, including hidden folders`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("外伝", directory = true), entry(".隠し", directory = true))
        val selection = FolderCover("作品/外伝/cover.jpg")
        fixture.coverIndex.folderCovers.value = mapOf(
            "作品/外伝" to selection,
            "作品/.隠し" to FolderCover("作品/.隠し/c.jpg"),
        )
        val list = viewModel()
        val spec = assertIs<CoverSpec.Folder>(list.shown().rows.single().cover)
        assertEquals(selection, spec.selection)
        assertNull(spec.chosen)
        assertTrue(fixture.coverBooks.listCalls.isEmpty())

        fixture.coverIndex.folderCovers.value = mapOf("作品/外伝" to FolderCover("作品/外伝/1.zip", entry = 3))
        assertEquals(3, assertIs<CoverSpec.Folder>(list.shown().rows.single().cover).selection?.entry)
        assertTrue(fixture.coverBooks.listCalls.isEmpty())
    }

    @Test
    fun `a chosen folder resolves its image or page only when generation searches it`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("外伝", directory = true))
        fixture.coverBooks.folders["作品/外伝"] = listOf(entry("cover.jpg"), entry("1.zip"))
        fixture.coverIndex.folderCovers.value = mapOf("作品/外伝" to FolderCover("作品/外伝/cover.jpg"))
        val list = viewModel()
        suspend fun resolved(): ThumbSubject.Book? {
            val ref = CoverRef(list.shown().rows.single().cover!!, 320)
            val subject = fixture.covers.request(ref, io.github.yuubiz.hirari.core.image.thumbnail.ThumbPriority.Visible).subject
            return assertIs<ThumbSubject.Folder>(subject).resolveChosen()
        }
        assertTrue(fixture.coverBooks.listCalls.isEmpty())
        assertEquals(locationBookId(place, "作品/外伝/cover.jpg"), resolved()?.id)
        fixture.coverIndex.folderCovers.value = mapOf("作品/外伝" to FolderCover("作品/外伝/1.zip", entry = 3))
        assertEquals(3, resolved()?.coverEntry)
        fixture.coverIndex.folderCovers.value = mapOf("作品/外伝" to FolderCover("作品/外伝/gone.jpg"))
        assertNull(resolved())
    }

    @Test
    fun `making a thumbnail again remakes the row at every size, the shown one first`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(entry("1巻.zip"))
        fixture.settings.update { it.copy(thumbSize = ThumbSize.Large) }
        val list = viewModel()

        list.remake(list.shown().rows.single())
        assertEquals(listOf(515, 309, 386), fixture.thumbnails.regenerated.map { it.sizePx })
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
        assertEquals(listOf(386, 309, 515), fixture.thumbnails.regenerated.map { it.sizePx })
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
        watch(fixture.covers.show(CoverRef(folder, 386)))
        fun folderLooks() = fixture.thumbnails.watches.count { it.request.subject.id == folder.id }
        assertEquals(1, folderLooks())
        val slow = CompletableDeferred<Unit>()
        fixture.coverBooks.gate = slow

        list.chooseFolderCover(list.shown().rows.single(), choose = true)
        screen.clear()
        slow.complete(Unit)
        assertEquals(listOf(386, 309, 515), fixture.thumbnails.regenerated.map { it.sizePx })
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
