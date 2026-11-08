package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.archive.ListingNeed
import io.github.yuubiz.hirari.core.data.ProvisionalCovers
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.thumbnail.CoverListing
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbPriority
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbSubject
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.locationBookId
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProvisionalCoverTest {
    private class RememberedCovers : ProvisionalCovers {
        val entries = mutableMapOf<BookId, Int>()

        override suspend fun remember(book: BookId, entryIndex: Int) {
            entries[book] = entryIndex
        }

        override suspend fun entry(book: BookId): Int? = entries[book]

        override suspend fun forget(book: BookId) {
            entries.remove(book)
        }
    }

    private val remembered = RememberedCovers()
    private val fixture = Fixture(remembered)
    private val covers = fixture.covers
    private val nas = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "", null, null, null)
    private val decoding = ImageDecoding(budgetBytes = 1 shl 20, parallelism = 1)

    private fun spec(cover: Int? = null) =
        CoverSpec.Book(locationBookId(nas, "s/1.rar"), Instant.EPOCH, 100, cover, ReadingDirection.RightToLeft, CoverOrigin.Archive(nas, "s/1.rar", null))

    private suspend fun openCover(spec: CoverSpec.Book) {
        assertIs<ThumbSubject.Book>(covers.request(CoverRef(spec, 320), ThumbPriority.Visible).subject).open(decoding)
    }

    private val needs get() = fixture.coverBooks.needs.map { it.second }

    @Test
    fun `a cover lists up to the first images, or up to the page the user chose`() = runTest {
        openCover(spec())
        openCover(spec(cover = 40))
        assertEquals(listOf(CoverListing.automatic, ListingNeed.UpTo(40)), needs)
    }

    @Test
    fun `a page preview lists up to that page`() = runTest {
        covers.preview(spec(), 12, 600)
        assertIs<ThumbSubject.Book>(fixture.thumbnails.previews.single().first).open(decoding)
        assertEquals(listOf(ListingNeed.UpTo(12)), needs)
    }

    private val sizes = listOf(320, 480)

    private val remade get() = fixture.thumbnails.regenerated.map { assertIs<ThumbSubject.Book>(it.subject) }

    @Test
    fun `a whole listing that puts another page first makes the automatic cover again from that page and forgets the provisional one`() = runTest {
        val id = spec().id
        remembered.entries[id] = 3
        covers.settle(id, firstEntry = 8, named = null, sizes, naming = { null }) { spec(cover = 40) }
        assertEquals(sizes, fixture.thumbnails.regenerated.map { it.sizePx })
        assertTrue(remade.all { it.coverEntry == null && it.firstEntry == 8 }, "under the automatic key, made from the page found")
        remade.first().open(decoding)
        assertEquals(listOf(ListingNeed.UpTo(8)), needs)
        assertNull(remembered.entries[id])
        openCover(spec())
        assertEquals(CoverListing.automatic, needs.last(), "the correction ends with its remake")
    }

    @Test
    fun `a whole listing that puts the same page first only forgets the provisional cover`() = runTest {
        val id = spec().id
        remembered.entries[id] = 3
        covers.settle(id, firstEntry = 3, named = null, sizes, naming = { null }) { error("nothing to remake") }
        assertNull(remembered.entries[id])
        remembered.entries[id] = 3
        covers.settle(id, firstEntry = null, named = null, sizes, naming = { null }) { error("a book without pages has nothing to show") }
        assertNull(remembered.entries[id])
    }

    @Test
    fun `a book without a provisional cover is left alone`() = runTest {
        covers.settle(BookId("other"), firstEntry = 1, named = null, sizes, naming = { null }) { error("nothing to remake") }
        assertTrue(remembered.entries.isEmpty())
    }

    @Test
    fun `a cover that cannot be found to make again stays provisional`() = runTest {
        val id = spec().id
        remembered.entries[id] = 3
        covers.settle(id, firstEntry = 8, named = null, sizes, naming = { null }) { null }
        assertEquals(3, remembered.entries[id])
    }

    @Test
    fun `a second settle of the same book waits for the first and finds nothing left to correct`() = runTest {
        val id = spec().id
        remembered.entries[id] = 3
        fixture.thumbnails.regenerating = { delay(1_000) }
        val first = async { covers.settle(id, firstEntry = 8, named = null, sizes, naming = { null }) { spec() } }
        val second = async { covers.settle(id, firstEntry = 8, named = null, sizes, naming = { null }) { spec() } }
        first.await()
        second.await()
        assertEquals(sizes, fixture.thumbnails.regenerated.map { it.sizePx })
        assertNull(remembered.entries[id])
    }

    @Test
    fun `a settle whose names were read in a charset chosen no longer keeps the provisional cover`() = runTest {
        val id = spec().id
        remembered.entries[id] = 3
        covers.settle(id, firstEntry = 8, named = null, sizes, naming = { Charsets.UTF_8 }) { error("nothing to remake") }
        assertTrue(fixture.thumbnails.regenerated.isEmpty())
        assertEquals(3, remembered.entries[id])
    }

    @Test
    fun `making a book's cover again waits for the settle running now`() = runTest {
        val id = spec().id
        remembered.entries[id] = 3
        fixture.thumbnails.regenerating = { delay(1_000) }
        val settle = async { covers.settle(id, firstEntry = 8, named = null, sizes, naming = { null }) { spec() } }
        val again = async { covers.regenerate(spec(), sizes) }
        settle.await()
        again.await()
        val made = remade.map { it.firstEntry }
        assertEquals(listOf(8, 8, null, null), made, "the settle's sizes, then the regeneration's")
    }

    @Test
    fun `the archive list makes every size of the cover again from its spec`() = runTest {
        val spec = spec()
        remembered.entries[spec.id] = 3
        fixture.services.settleProvisionalCover(spec.id, 8, named = null, spec)
        assertEquals(covers.sizesFrom(fixture.settings.value.value.thumbSize), fixture.thumbnails.regenerated.map { it.sizePx })
        assertTrue(remade.all { it.id == spec.id && it.firstEntry == 8 })
        assertNull(remembered.entries[spec.id])
    }
}
