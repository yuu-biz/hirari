package io.github.yuubiz.hirari.core.image.thumbnail

import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageEntry
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReadingDirection

/**
 * The page a thumbnail shows (docs/spec.md section 4.4): the one with the `entryIndex` [coverEntry] that the user
 * chose, and otherwise the first of [pages], which are in natural order. A chosen page the book no longer has
 * gives the first page. `null` for a book without pages.
 */
internal fun coverPage(pages: List<PageEntry>, coverEntry: Int?): PageEntry? =
    coverEntry?.let { entry -> pages.firstOrNull { it.entryIndex == entry } } ?: pages.firstOrNull()

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
    BookFailure.NotFound, BookFailure.NoPermission, BookFailure.NoRoomForCopy, BookFailure.ReadError -> Made.Unavailable
}

/** Why listing an archive stopped before it found a page: a read that failed may pass, a broken archive does not. */
internal fun FailureReason.made(): Made = when (this) {
    FailureReason.Corrupt, FailureReason.Truncated -> Made.Unreadable
    FailureReason.ReadError, FailureReason.ConnectionLost, FailureReason.Timeout -> Made.Unavailable
}

/** A page that did not give an image. A failure that [PageResult.Failed.retryable] allows again is not final. */
internal fun PageResult<*>.made(): Made = when (this) {
    is PageResult.Ready -> error("a ready page is not a failure")
    is PageResult.Failed -> if (retryable) Made.Unavailable else Made.Unreadable
    is PageResult.Unsupported -> Made.Unreadable
}
