package io.github.yuubiz.hirari.core.image.thumbnail

import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.DecodePlan
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.planDecode
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverTest {
    private fun page(index: Int, entryIndex: Int) = PageEntry(index, entryIndex, ByteArray(0), "$index.jpg", null, null)

    private val pages = listOf(page(0, 7), page(1, 3), page(2, 0))

    @Test
    fun theCoverIsTheFirstPageInNaturalOrderUnlessTheUserChoseOne() {
        assertEquals(0, coverPage(pages, null, listingFinal = true)!!.index)
        assertEquals(1, coverPage(pages, 3, listingFinal = true)!!.index)
        assertEquals(2, coverPage(pages, 0, listingFinal = true)!!.index)
    }

    @Test
    fun aChosenPageTheBookNoLongerHasGivesTheFirstPage() {
        assertEquals(0, coverPage(pages, 99, listingFinal = true)!!.index)
    }

    @Test
    fun aListingThatMayGoOnGivesOnlyAChosenPageItReached() {
        assertEquals(1, coverPage(pages, 3, listingFinal = false)!!.index)
        assertNull(coverPage(pages, 99, listingFinal = false))
        assertNull(coverPage(pages, null, listingFinal = false))
    }

    @Test
    fun aBookWithoutPagesHasNoCover() {
        assertNull(coverPage(emptyList(), null, listingFinal = true))
        assertNull(coverPage(emptyList(), 3, listingFinal = true))
    }

    @Test
    fun aWideCoverIsDecodedAtTwiceTheWidthSoItsHalfFillsTheLongSide() {
        assertEquals(PixelSize(600, 300), coverTarget(PageShape.Known(2400, 1700), 300))
        assertEquals(PixelSize(300, 300), coverTarget(PageShape.Known(1200, 1700), 300))
        assertEquals(PixelSize(300, 300), coverTarget(PageShape.Known(1000, 1000), 300))
    }

    @Test
    fun theHalfOfAWideCoverFitsTheLongSideWhateverItsProportion() {
        for ((width, height) in listOf(2400 to 1700, 2000 to 1000, 3000 to 1000, 1100 to 1000)) {
            val target = coverTarget(PageShape.Known(width, height), 300)
            val scale = minOf(1.0, target.width / width.toDouble(), target.height / height.toDouble())
            val half = maxOf(width * scale / 2, height * scale)
            assertEquals("$width x $height", 300.0, half, 1.0)
        }
    }

    private val wide = PageShape.Known(2400, 1700)

    @Test
    fun aCoverBoundOnTheRightShowsItsRightHalfAndOneBoundOnTheLeftItsLeftHalf() {
        assertEquals(CropBox(left = 50, width = 50), coverCrop(wide, 100, ReadingDirection.RightToLeft))
        assertEquals(CropBox(left = 0, width = 50), coverCrop(wide, 100, ReadingDirection.LeftToRight))
    }

    @Test
    fun theTwoHalvesOfAnOddWidthStayInsideTheImage() {
        val right = coverCrop(wide, 101, ReadingDirection.RightToLeft)!!
        val left = coverCrop(wide, 101, ReadingDirection.LeftToRight)!!
        assertEquals(50, right.width)
        assertEquals(101, right.left + right.width)
        assertEquals(50, left.left + left.width)
    }

    @Test
    fun aPageThatIsNotWiderThanHighIsNotCut() {
        assertNull(coverCrop(PageShape.Known(600, 1000), 180, ReadingDirection.RightToLeft))
        assertNull(coverCrop(PageShape.Known(1000, 1000), 300, ReadingDirection.LeftToRight))
    }

    @Test
    fun aWidePageIsCutEvenWhenItsDecodeRoundsToASquare() {
        val shape = PageShape.Known(1001, 1000)
        val plan = planDecode(1001, 1000, coverTarget(shape, 300), averagingLimit = 8, bytesPerPixel = 4, budgetBytes = 64L shl 20)
        val decoded = (plan as DecodePlan.Decode).output
        assertEquals(PixelSize(300, 300), decoded)
        assertEquals(CropBox(left = 150, width = 150), coverCrop(shape, decoded.width, ReadingDirection.RightToLeft))
    }

    @Test
    fun aBookThatIsEncryptedOrBrokenIsRecordedAndOneThatCouldBeReadLaterIsNot() {
        assertEquals(Made.Unreadable, BookFailure.Encrypted.made())
        assertEquals(Made.Unreadable, BookFailure.Corrupt.made())
        assertEquals(Made.Unreadable, BookFailure.Truncated.made())
        for (failure in listOf(BookFailure.NotFound, BookFailure.NoPermission, BookFailure.NoRoomForCopy, BookFailure.ReadError)) {
            assertEquals(failure.name, Made.Unavailable, failure.made())
        }
    }

    @Test
    fun aPageFailureIsRecordedUnlessItMayBeRetried() {
        assertEquals(Made.Unreadable, PageResult.Failed(FailureReason.Corrupt, retryable = false).made())
        assertEquals(Made.Unreadable, PageResult.Failed(FailureReason.Truncated, retryable = false).made())
        assertEquals(Made.Unavailable, PageResult.Failed(FailureReason.Timeout, retryable = true).made())
        assertEquals(Made.Unavailable, PageResult.Failed(FailureReason.ConnectionLost, retryable = true).made())
        assertEquals(Made.Unreadable, PageResult.Unsupported(UnsupportedKind.Encrypted).made())
        assertEquals(Made.Unreadable, PageResult.Unsupported(UnsupportedKind.NotAnImage).made())
        assertEquals(Made.Unreadable, PageResult.Unsupported(UnsupportedKind.TooLarge(1, 1, 1)).made())
    }
}
