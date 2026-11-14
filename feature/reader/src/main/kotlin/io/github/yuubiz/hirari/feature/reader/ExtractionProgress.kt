package io.github.yuubiz.hirari.feature.reader

/**
 * 展開の状況 (docs/spec.md section 4.8): the extraction of a solid archive is at the [at]th entry in archive order,
 * counted from 1, and the view can be built once it reaches the [of]th.
 */
internal data class ExtractionProgress(val at: Int, val of: Int)

/**
 * The progress of an extraction pass at the 0-based entry index [passAt], or null when there is none to show: the book
 * is not extracted ([passAt] is null), no page waits, or the pass is already past the last entry a waiting page needs,
 * so the page is being decoded. [waiting] are the archive-order indexes of the view's pages that are not prepared.
 */
internal fun extractionProgress(passAt: Int?, waiting: List<Int>): ExtractionProgress? {
    if (passAt == null) return null
    val last = waiting.maxOrNull() ?: return null
    return if (passAt > last) null else ExtractionProgress(passAt + 1, last + 1)
}
