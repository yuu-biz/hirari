package io.github.yuubiz.hirari.core.image.thumbnail

import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.DecodeLimits
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReadingDirection
import kotlin.math.ceil

/**
 * The page a thumbnail shows (docs/spec.md section 4.4): the one with the `entryIndex` [coverEntry] that the user
 * chose, and otherwise the first of [pages], which are in natural order. A chosen page the book no longer has
 * gives the first page. `null` for a book without pages.
 *
 * [listingFinal] is false when the listing of [pages] stopped for a reason that may pass. Entries it did not
 * reach may then hold the chosen page or sort before the first, so only a chosen page it did reach is the cover,
 * and anything else is `null`.
 *
 * [counted] is true when the listing counted the first images ([CoverListing.automatic]), whether it stopped at them or
 * found fewer before the end: the first page is then the first of the images it counted ([CoverListing.firstCounted]).
 */
internal fun coverPage(pages: List<PageEntry>, coverEntry: Int?, listingFinal: Boolean, counted: Boolean = false): PageEntry? {
    val chosen = coverEntry?.let { entry -> pages.firstOrNull { it.entryIndex == entry } }
    return when {
        chosen != null -> chosen
        !listingFinal -> null
        counted -> CoverListing.firstCounted(pages)
        else -> pages.firstOrNull()
    }
}

/**
 * The size to decode a cover of [shape] to. A cover wider than high is cut to one half (see [coverCrop]), so it
 * is decoded at up to twice [sizePx] wide: the half then has [sizePx] on its long side, whichever way the page is
 * proportioned.
 */
internal fun coverTarget(shape: PageShape.Known, sizePx: Int): PixelSize =
    if (shape.isWide) PixelSize(sizePx * 2, sizePx) else PixelSize(sizePx, sizePx)

/** The columns of a decoded cover that stay: [width] of them from [left]. */
internal data class CropBox(val left: Int, val width: Int)

/**
 * The half of a cover scanned as a spread that is its front (docs/spec.md section 4.4), in a decode of [shape]
 * [width] pixels wide: the right half for a book bound on the right, the left half for one bound on the left.
 * `null` when the page is not wider than high. The page decides, not the decode, which rounding can make square.
 */
internal fun coverCrop(shape: PageShape.Known, width: Int, direction: ReadingDirection): CropBox? {
    if (!shape.isWide) return null
    val half = width / 2
    return CropBox(left = if (direction == ReadingDirection.RightToLeft) width - half else 0, width = half)
}

/**
 * The range of the cover page to cut: [crop], when [page] is the page the user chose with [coverEntry]. A range
 * belongs to its page, so when the chosen page is gone and the first page stands in for it, the first page is shown
 * whole (docs/spec.md section 4.4).
 */
internal fun rangeOf(page: PageEntry, coverEntry: Int?, crop: CoverCrop?): CoverCrop? =
    crop?.takeIf { coverEntry != null && page.entryIndex == coverEntry }

/**
 * The size to decode a cover page of [shape] to so that the part [crop] of it has [sizePx] on its long side, the
 * way [coverTarget] decodes a wide cover at twice the width (docs/spec.md section 4.4). The decode is never larger
 * than the longest bitmap side of section 3.7, so a small range of a large page may come out under [sizePx]; a
 * decode never enlarges a page, so a page smaller than that is taken as it is.
 */
internal fun cropTarget(shape: PageShape.Known, crop: CoverCrop, sizePx: Int): PixelSize {
    val partWidth = shape.width.toDouble() * crop.width / CoverCrop.SCALE
    val partHeight = shape.height.toDouble() * crop.height / CoverCrop.SCALE
    var scale = sizePx / maxOf(partWidth, partHeight)
    scale = minOf(scale, DecodeLimits.MAX_BITMAP_SIDE / maxOf(shape.width, shape.height).toDouble())
    return PixelSize(ceil(shape.width * scale - ROUNDING_SLACK).toInt().coerceAtLeast(1), ceil(shape.height * scale - ROUNDING_SLACK).toInt().coerceAtLeast(1))
}

/** A product that comes out a hair over a whole number of pixels is that number, not one more. */
private const val ROUNDING_SLACK = 1e-6

/** The pixels of a decode of [width] x [height] that [crop] covers: the edges are rounded outward, so no pixel of the range is lost. */
internal data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun cropRect(crop: CoverCrop, width: Int, height: Int): CropRect {
    fun low(edge: Int, length: Int) = (edge.toLong() * length / CoverCrop.SCALE).toInt().coerceIn(0, length - 1)
    fun high(edge: Int, length: Int) = ((edge.toLong() * length + CoverCrop.SCALE - 1) / CoverCrop.SCALE).toInt().coerceIn(1, length)
    val left = low(crop.left, width)
    val top = low(crop.top, height)
    return CropRect(left, top, maxOf(high(crop.right, width) - left, 1), maxOf(high(crop.bottom, height) - top, 1))
}

/** What making a thumbnail came to. */
internal sealed interface Made {
    class Webp(val bytes: ByteArray) : Made

    /** The book has nothing to show and will not have, as long as the file stays the same: record it. */
    data object Unreadable : Made

    /** The attempt failed for a reason that may pass: do not record it. */
    data object Unavailable : Made
}

/** A book that did not open: encrypted or broken is final, a missing permission or a read error may pass. */
internal fun BookFailure.made(): Made = when (this) {
    BookFailure.Encrypted, BookFailure.Corrupt, BookFailure.Truncated -> Made.Unreadable
    BookFailure.PasswordRequired -> Made.Unavailable
    BookFailure.NotFound, BookFailure.NoPermission, BookFailure.NoRoomForCopy, BookFailure.ReadError -> Made.Unavailable
}

/** Why listing an archive stopped before it found a page: a read that failed may pass, a broken archive does not. */
internal fun FailureReason.made(): Made = when (this) {
    FailureReason.Corrupt, FailureReason.Truncated -> Made.Unreadable
    FailureReason.PasswordRequired -> Made.Unavailable
    FailureReason.ReadError, FailureReason.ConnectionLost, FailureReason.Timeout -> Made.Unavailable
}

/** A page that did not give an image. A failure that [PageResult.Failed.retryable] allows again is not final. */
internal fun PageResult<*>.made(): Made = when (this) {
    is PageResult.Ready -> error("a ready page is not a failure")
    is PageResult.Failed -> if (retryable || reason == FailureReason.PasswordRequired) Made.Unavailable else Made.Unreadable
    is PageResult.Unsupported -> Made.Unreadable
}
