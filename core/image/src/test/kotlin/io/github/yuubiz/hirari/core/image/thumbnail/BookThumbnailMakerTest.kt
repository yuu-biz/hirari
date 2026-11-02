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
}
