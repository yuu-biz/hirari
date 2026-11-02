package io.github.yuubiz.hirari.core.image.thumbnail

import io.github.yuubiz.hirari.core.image.BookContent
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.image.PageBytes
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class BookThumbnailMakerTest {
    private val decoding = ImageDecoding(budgetBytes = 64L shl 20, parallelism = 1)
    private val maker = BookThumbnailMaker(decoding)

    private val noBytes = object : PageBytes {
        override suspend fun read(page: PageEntry): PageResult<ByteArray> = error("no page to read")

        override fun close() = Unit
    }

    private fun archiveWithoutPages(indexError: FailureReason?) = ThumbSubject.Book(BookId("a"), Instant.EPOCH, byteSize = 100) {
        BookResult.Opened(OpenBook(BookContent.Images(noBytes), Container.Zip, pages = emptyList(), indexError, it))
    }

    private suspend fun make(book: ThumbSubject.Book) = maker.make(book, 300, ReadingDirection.RightToLeft)

    private fun page(index: Int, entryIndex: Int, name: String) = PageEntry(index, entryIndex, ByteArray(0), name, null, null)

    /** The names of the pages read, each of which answers that it cannot be shown. */
    private val read = ArrayList<String>()

    private val unshownBytes = object : PageBytes {
        override suspend fun read(page: PageEntry): PageResult<ByteArray> {
            read += page.name
            return PageResult.Unsupported(UnsupportedKind.Encrypted)
        }

        override fun close() = Unit
    }

    /** An archive whose listing gave [pages] in natural order and stopped there for [indexError]. */
    private fun archive(pages: List<PageEntry>, indexError: FailureReason?, coverEntry: Int? = null) =
        ThumbSubject.Book(BookId("a"), Instant.EPOCH, byteSize = 100, coverEntry = coverEntry) {
            BookResult.Opened(OpenBook(BookContent.Images(unshownBytes), Container.Zip, pages, indexError, it))
        }

    @Test
    fun anArchiveWhoseListingStoppedForAPassingReasonBeforeAnImageMayBeReadLater() = runTest {
        for (reason in listOf(FailureReason.ReadError, FailureReason.ConnectionLost, FailureReason.Timeout)) {
            assertEquals(reason.name, Made.Unavailable, make(archiveWithoutPages(reason)))
        }
    }

    @Test
    fun anArchiveWithoutImagesOrBrokenBeforeTheFirstIsUnreadable() = runTest {
        for (reason in listOf(null, FailureReason.Corrupt, FailureReason.Truncated)) {
            assertEquals("$reason", Made.Unreadable, make(archiveWithoutPages(reason)))
        }
    }

    @Test
    fun aListingThatStoppedForAPassingReasonDoesNotTellTheFirstPage() = runTest {
        val listed = listOf(page(0, entryIndex = 0, "010.jpg"))
        assertEquals(Made.Unavailable, make(archive(listed, FailureReason.ConnectionLost)))
        assertEquals("a page that may not be first was read", emptyList<String>(), read)
    }

    @Test
    fun aChosenPageTheListingHasNotReachedMayBeReadLater() = runTest {
        val listed = listOf(page(0, entryIndex = 0, "001.jpg"))
        assertEquals(Made.Unavailable, make(archive(listed, FailureReason.ReadError, coverEntry = 5)))
        assertEquals("the first page was read in place of the chosen one", emptyList<String>(), read)
    }

    @Test
    fun aChosenPageTheListingReachedIsTheCoverThoughTheListingStopped() = runTest {
        val listed = listOf(page(0, entryIndex = 0, "001.jpg"), page(1, entryIndex = 5, "002.jpg"))
        assertEquals(Made.Unreadable, make(archive(listed, FailureReason.Timeout, coverEntry = 5)))
        assertEquals(listOf("002.jpg"), read)
    }

    @Test
    fun aBrokenArchiveShowsTheFirstPageItsListingGives() = runTest {
        val listed = listOf(page(0, entryIndex = 1, "010.jpg"))
        assertEquals(Made.Unreadable, make(archive(listed, FailureReason.Truncated)))
        assertEquals(listOf("010.jpg"), read)
    }
}
