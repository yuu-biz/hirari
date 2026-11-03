package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.image.thumbnail.PagePreview
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbSubject
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ThumbSize
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CoverRangeViewModelTest {
    private val fixture = Fixture()
    private val place = LibraryLocation.SafTree(LocationId(1), "漫画", UriString("content://tree/manga"))
    private val indexed = book(place.id, "作品/book.zip")

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun viewModel(entry: Int = 3, key: CoverRangeKey = CoverRangeKey(indexed.id, entry)): CoverRangeViewModel {
        fixture.locations.rows.value = listOf(place)
        fixture.books.put(indexed)
        return CoverRangeViewModel(CompletableDeferred(fixture.services), key)
    }

    private fun CoverRangeViewModel.failure() = assertIs<CoverRangeUi.Failed>(state.value).reason

    @Test
    fun `a book that is not in the index has nothing to frame`() = runTest {
        val model = viewModel(key = CoverRangeKey(book(place.id, "other.zip").id, 0))
        assertEquals(CoverRangeFailure.NotFound, model.failure())
    }

    @Test
    fun `a book whose place is gone has nothing to frame`() = runTest {
        val model = viewModel()
        fixture.locations.rows.value = emptyList()
        model.retry()
        assertEquals(CoverRangeFailure.NotFound, model.failure())
    }

    @Test
    fun `a text has no cover to frame`() = runTest {
        val text: Book = indexed.copy(format = BookFormat.Text(null))
        val model = viewModel()
        fixture.books.put(text)
        model.retry()
        assertEquals(CoverRangeFailure.NoImages, model.failure())
    }

    @Test
    fun `the page is asked for by its entry index with the book as a cover is opened`() = runTest {
        fixture.thumbnails.preview = PagePreview.Missing
        val model = viewModel(entry = 3)
        assertEquals(CoverRangeFailure.PageMissing, model.failure())
        val (subject, entry, side) = fixture.thumbnails.previews.single()
        assertEquals(indexed.id, subject.id)
        assertEquals(3, entry)
        assertEquals(3, subject.coverEntry)
        assertNull(subject.coverCrop)
        assertEquals(true, side >= 1024)
    }

    @Test
    fun `a page that cannot be shown says whether it may come back`() = runTest {
        fixture.thumbnails.preview = PagePreview.Failed
        assertEquals(CoverRangeFailure.Unreadable, viewModel().failure())
        fixture.thumbnails.preview = PagePreview.Unavailable
        assertEquals(CoverRangeFailure.Unavailable, viewModel().failure())
    }

    @Test
    fun `choosing a frame stores the page with the range and makes the thumbnail again at every size`() = runTest {
        fixture.thumbnails.preview = PagePreview.Unavailable
        fixture.settings.value.value = fixture.settings.value.value.copy(thumbSize = ThumbSize.Medium)
        fixture.coverIndex.directions.value = mapOf(indexed.id to ReadingDirection.LeftToRight)
        val model = viewModel(entry = 3)

        model.choose(CropFrame(0.1f, 0.2f, 0.6f, 0.9f))

        val crop = CoverCrop(1000, 2000, 6000, 9000)
        assertEquals(listOf(Triple(indexed.id, 3 as Int?, crop as CoverCrop?)), fixture.books.coverSets)
        val remade = fixture.thumbnails.regenerated
        assertEquals(ThumbSize.entries.size, remade.size)
        assertEquals(setOf(crop), remade.map { (it.subject as ThumbSubject.Book).coverCrop }.toSet())
        assertEquals(setOf(3), remade.map { (it.subject as ThumbSubject.Book).coverEntry }.toSet())
        assertEquals(setOf(ReadingDirection.LeftToRight), remade.map { it.direction }.toSet())
        assertEquals(ThumbSize.entries.size, remade.map { it.sizePx }.toSet().size)
        assertEquals(fixture.covers.sizePx(ThumbSize.Medium), remade.first().sizePx)
    }

    @Test
    fun `a write that fails is shown as a failed save and does not end the process, and choosing again stores it`() = runTest {
        val model = viewModel(entry = 3)
        fixture.books.coverFailure = java.io.IOException("no space left")

        model.choose(CropFrame(0.1f, 0.2f, 0.6f, 0.9f))

        assertEquals(emptyList<Throwable>(), fixture.uncaught)
        assertEquals(CoverSave.Failed, model.save.value)
        assertEquals(emptyList(), fixture.books.coverSets)
        assertEquals(emptyList(), fixture.thumbnails.regenerated)

        fixture.books.coverFailure = null
        model.choose(CropFrame(0.1f, 0.2f, 0.6f, 0.9f))

        assertEquals(1, fixture.books.coverSets.size)
        assertEquals(ThumbSize.entries.size, fixture.thumbnails.regenerated.size)
    }

    @Test
    fun `the choice is saved only once the write has completed, and the thumbnails are made after`() = runTest {
        val model = viewModel(entry = 3)
        val gate = CompletableDeferred<Unit>()
        fixture.books.coverGate = gate

        model.choose(CropFrame(0.1f, 0.2f, 0.6f, 0.9f))
        model.choose(CropFrame(0.2f, 0.2f, 0.7f, 0.9f))

        assertEquals(CoverSave.Saving, model.save.value)
        assertEquals(emptyList(), fixture.books.coverSets)

        gate.complete(Unit)

        assertEquals(CoverSave.Saved, model.save.value)
        assertEquals(listOf(Triple(indexed.id, 3 as Int?, CoverCrop(1000, 2000, 6000, 9000) as CoverCrop?)), fixture.books.coverSets)
        assertEquals(ThumbSize.entries.size, fixture.thumbnails.regenerated.size)
    }

    @Test
    fun `a thumbnail that cannot be made again after the choice is stored does not end the process`() = runTest {
        val model = viewModel(entry = 3)
        fixture.settings.failure = java.io.IOException("unreadable")

        model.choose(CropFrame(0.1f, 0.2f, 0.6f, 0.9f))

        assertEquals(emptyList<Throwable>(), fixture.uncaught)
        assertEquals(1, fixture.books.coverSets.size)
    }

    @Test
    fun `dropping the range keeps the page as the cover and remakes it whole`() = runTest {
        val model = viewModel(entry = 3)
        model.choose(CropFrame(0.1f, 0.2f, 0.6f, 0.9f))
        fixture.thumbnails.regenerated.clear()

        model.dropRange()

        assertEquals(Triple(indexed.id, 3 as Int?, null as CoverCrop?), fixture.books.coverSets.last())
        assertEquals(setOf<CoverCrop?>(null), fixture.thumbnails.regenerated.map { (it.subject as ThumbSubject.Book).coverCrop }.toSet())
    }
}
