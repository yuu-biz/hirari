package io.github.yuubiz.hirari.feature.reader

import androidx.annotation.StringRes
import io.github.yuubiz.hirari.core.image.OpenStep
import java.util.Locale

/**
 * What the reader shows under the indicator while a book opens (docs/spec.md section 4.8, 本を開いている間): the name
 * of the work, its progress as the string [detail] filled with [args] when the work has one, and [fraction] when the
 * progress is a share, which makes the indicator a ring.
 */
internal class OpeningLine(
    @StringRes val name: Int,
    @StringRes val detail: Int? = null,
    val args: List<Any> = emptyList(),
    val fraction: Float? = null,
)

internal fun OpenStep.line(): OpeningLine = when (this) {
    OpenStep.Preparing -> OpeningLine(R.string.opening_preparing)
    OpenStep.Connecting -> OpeningLine(R.string.opening_connecting)
    OpenStep.ListingFolder -> OpeningLine(R.string.opening_listing_folder)
    OpenStep.OpeningPdf -> OpeningLine(R.string.opening_pdf)
    is OpenStep.Copying -> {
        val whole = total
        if (whole != null && whole > 0) {
            val share = (bytes.toDouble() / whole).coerceIn(0.0, 1.0)
            OpeningLine(R.string.opening_copying, R.string.opening_copied_of, listOf(megabytes(bytes), megabytes(whole), percent(share)), share.toFloat())
        } else {
            OpeningLine(R.string.opening_copying, R.string.opening_copied, listOf(megabytes(bytes)))
        }
    }
    // A listing call that has not told a count yet shows its name alone, not a 0.
    is OpenStep.Listing -> {
        val share = fraction
        when {
            entries <= 0 -> OpeningLine(R.string.opening_listing)
            share != null -> OpeningLine(R.string.opening_listing, R.string.opening_items_of, listOf(count(entries), percent(share.toDouble())), share)
            else -> OpeningLine(R.string.opening_listing, R.string.opening_items, listOf(count(entries)))
        }
    }
}

private const val MB = 1024.0 * 1024.0

private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%,.1f", bytes / MB)

internal fun count(entries: Int) = String.format(Locale.ROOT, "%,d", entries)

/** The whole percent below [share]. The epsilon keeps a share such as 0.45f, which is a hair under, from showing 44. */
private fun percent(share: Double) = (share * 100 + 1e-4).toInt().coerceIn(0, 100)
