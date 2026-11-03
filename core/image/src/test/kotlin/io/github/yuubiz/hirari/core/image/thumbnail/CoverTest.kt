package io.github.yuubiz.hirari.core.image.thumbnail

import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.DecodeLimits
import io.github.yuubiz.hirari.core.image.DecodePlan
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.planDecode
import io.github.yuubiz.hirari.core.model.CoverCrop
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
    fun aRangeBelongsToTheChosenPageAndIsDroppedWhenAnotherPageStandsInForIt() {
        val crop = CoverCrop(0, 0, 5000, 5000)
        assertEquals(crop, rangeOf(pages[1], coverEntry = 3, crop))
        assertNull(rangeOf(pages[0], coverEntry = 3, crop))
        assertNull(rangeOf(pages[0], coverEntry = null, crop))
        assertNull(rangeOf(pages[1], coverEntry = 3, crop = null))
    }

    @Test
    fun aRangeIsDecodedAtTheScaleThatGivesItsLongSideTheThumbnailSize() {
        val page = PageShape.Known(2000, 3000)
        // Half the width and a third of the height: 1000 x 1000 of the page, so the page is decoded at 0.3.
        assertEquals(PixelSize(600, 900), cropTarget(page, CoverCrop(2500, 0, 7500, 3333), 300))
        // The whole page is the page fitted to the size.
        assertEquals(PixelSize(200, 300), cropTarget(page, CoverCrop(0, 0, 10_000, 10_000), 300))
    }

    @Test
    fun aSmallRangeOfALargePageIsDecodedNoLargerThanTheBitmapLimit() {
        val target = cropTarget(PageShape.Known(6000, 8000), CoverCrop(0, 0, 1000, 1000), 600)
        assertEquals(DecodeLimits.MAX_BITMAP_SIDE, maxOf(target.width, target.height))
        assertEquals(3072, target.width)
    }

    @Test
    fun aRangeOfASmallPageAsksForMoreThanThePageHasAndNeverEnlargesIt() {
        val page = PageShape.Known(400, 600)
        val target = cropTarget(page, CoverCrop(0, 0, 5000, 5000), 600)
        val plan = planDecode(400, 600, target, averagingLimit = 8, bytesPerPixel = 4, budgetBytes = 64L shl 20) as DecodePlan.Decode
        assertEquals(PixelSize(400, 600), plan.output)
    }

    @Test
    fun theRangeIsCutFromTheDecodeWithItsEdgesRoundedOutward() {
        assertEquals(CropRect(25, 50, 50, 25), cropRect(CoverCrop(2500, 5000, 7500, 7500), 100, 100))
        assertEquals(CropRect(0, 0, 4, 3), cropRect(CoverCrop(0, 0, 3500, 2001), 10, 10))
        assertEquals(CropRect(9, 9, 1, 1), cropRect(CoverCrop(9000, 9000, 10_000, 10_000), 10, 10))
    }

    @Test
    fun aRangeStaysInsideATinyDecode() {
        val rect = cropRect(CoverCrop(9000, 9000, 10_000, 10_000), 3, 2)
        assertEquals(true, rect.left + rect.width <= 3 && rect.top + rect.height <= 2 && rect.width >= 1 && rect.height >= 1)
        assertEquals(CropRect(0, 0, 1, 1), cropRect(CoverCrop(0, 0, 1000, 1000), 1, 1))
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
