package io.github.yuubiz.hirari.core.image.thumbnail

import io.github.yuubiz.hirari.core.image.BookContent
import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.image.PageBytes
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class BookThumbnailMakerTest {
    private val decoding = ImageDecoding(budgetBytes = 64L shl 20, parallelism = 1)
    private val maker = BookThumbnailMaker(decoding)

    @Test
    fun changingDecodingLeavesAnOpenBookOnItsOriginalBudgetAndAppliesToTheNextMake() = runTest {
        val opened = CompletableDeferred<ImageDecoding>()
        val release = CompletableDeferred<Unit>()
        val used = ArrayList<ImageDecoding>()
        val book = ThumbSubject.Book(BookId("changing"), Instant.EPOCH, byteSize = 100) { current ->
            used += current
            opened.complete(current)
            release.await()
            BookResult.Opened(OpenBook(BookContent.Images(noBytes), Container.Zip, emptyList(), null, current))
        }
        val first = async { make(book) }
        assertEquals(decoding, opened.await())
        val next = ImageDecoding(32L shl 20, 4)
        maker.decoding = next
        release.complete(Unit)
        assertEquals(Made.Unreadable, first.await())
        assertEquals(Made.Unreadable, make(book))
        assertEquals(listOf(decoding, next), used)
    }

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

    /** A RAR whose listing stopped where its need was met, after [pages]. */
    private fun partialRar(pages: List<PageEntry>, firstEntry: Int? = null) =
        ThumbSubject.Book(BookId("a"), Instant.EPOCH, byteSize = 100, firstEntry = firstEntry) {
            BookResult.Opened(OpenBook(BookContent.Images(unshownBytes), Container.Rar, pages, null, it, partial = true))
        }

    private fun named(index: Int, entryIndex: Int, name: String) = PageEntry(index, entryIndex, name.toByteArray(), name, null, null)

    @Test
    fun aCoverMadeAgainFromTheFirstPageOfAWholeListingIsThatPage() = runTest {
        val listed = listOf(named(0, entryIndex = 4, "001.jpg"), named(1, entryIndex = 7, "002.jpg"))
        assertEquals(Made.Unreadable, make(partialRar(listed, firstEntry = 7)))
        assertEquals(listOf("002.jpg"), read)
    }

    @Test
    fun aCoverFromTheFirstImagesIsTheFirstOfTheImagesTheListingCounted() = runTest {
        val listed = listOf(named(0, entryIndex = 0, "000.dat"), named(1, entryIndex = 1, "001.jpg"))
        assertEquals(Made.Unreadable, make(partialRar(listed)))
        assertEquals(listOf("001.jpg"), read)
    }

    @Test
    fun aRangeIsCutFromTheChosenPageAndTheSameRulesFindThatPage() = runTest {
        val listed = listOf(page(0, entryIndex = 0, "001.jpg"), page(1, entryIndex = 5, "002.jpg"))
        val ranged = ThumbSubject.Book(BookId("a"), Instant.EPOCH, byteSize = 100, coverEntry = 5, coverCrop = CoverCrop(0, 0, 5000, 5000)) {
            BookResult.Opened(OpenBook(BookContent.Images(unshownBytes), Container.Zip, listed, null, it))
        }
        assertEquals(Made.Unreadable, make(ranged))
        assertEquals(listOf("002.jpg"), read)
    }

    private suspend fun preview(listed: List<PageEntry>, entryIndex: Int, indexError: FailureReason? = null) =
        maker.preview(archive(listed, indexError), entryIndex, 1000)

    @Test
    fun aPagePreviewNamesThePageByItsEntryIndexNotItsPosition() = runTest {
        val listed = listOf(page(0, entryIndex = 7, "001.jpg"), page(1, entryIndex = 3, "002.jpg"))
        assertEquals(PagePreview.Failed, preview(listed, entryIndex = 3))
        assertEquals(listOf("002.jpg"), read)
    }

    @Test
    fun aPagePreviewOfAPageTheBookDoesNotHaveIsMissing() = runTest {
        val listed = listOf(page(0, entryIndex = 7, "001.jpg"))
        assertEquals(PagePreview.Missing, preview(listed, entryIndex = 1))
        assertEquals(emptyList<String>(), read)
    }

    @Test
    fun aPagePreviewOfAPageTheListingHasNotReachedMayBeShownLater() = runTest {
        val listed = listOf(page(0, entryIndex = 0, "001.jpg"))
        for (reason in listOf(FailureReason.ReadError, FailureReason.ConnectionLost, FailureReason.Timeout)) {
            assertEquals(reason.name, PagePreview.Unavailable, preview(listed, entryIndex = 5, indexError = reason))
        }
        assertEquals(emptyList<String>(), read)
    }

    @Test
    fun aPagePreviewOfAPageBeyondAListingThatBrokeForGoodIsMissing() = runTest {
        val listed = listOf(page(0, entryIndex = 0, "001.jpg"))
        for (reason in listOf(FailureReason.Corrupt, FailureReason.Truncated)) {
            assertEquals(reason.name, PagePreview.Missing, preview(listed, entryIndex = 5, indexError = reason))
        }
    }

    @Test
    fun aPagePreviewOfAPageTheListingReachedIsShownThoughTheListingStopped() = runTest {
        val listed = listOf(page(0, entryIndex = 0, "001.jpg"), page(1, entryIndex = 5, "002.jpg"))
        assertEquals(PagePreview.Failed, preview(listed, entryIndex = 5, indexError = FailureReason.Timeout))
        assertEquals(listOf("002.jpg"), read)
    }

    @Test
    fun aPagePreviewOfABookThatDoesNotOpenIsFailedOrUnavailableAsThumbnailsAre() = runTest {
        fun failing(failure: BookFailure) = ThumbSubject.Book(BookId("a"), Instant.EPOCH, byteSize = 100) { BookResult.Failed(failure) }
        assertEquals(PagePreview.Failed, maker.preview(failing(BookFailure.Encrypted), 0, 1000))
        assertEquals(PagePreview.Unavailable, maker.preview(failing(BookFailure.ReadError), 0, 1000))
    }

    @Test
    fun aBrokenArchiveShowsTheFirstPageItsListingGives() = runTest {
        val listed = listOf(page(0, entryIndex = 1, "010.jpg"))
        assertEquals(Made.Unreadable, make(archive(listed, FailureReason.Truncated)))
        assertEquals(listOf("010.jpg"), read)
    }
}
