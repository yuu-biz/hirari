package io.github.yuubiz.hirari.core.image.thumbnail

import io.github.yuubiz.hirari.core.archive.ListingNeed
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.image.PageCandidate
import io.github.yuubiz.hirari.core.image.pageCandidate
import io.github.yuubiz.hirari.core.model.PageEntry

/** How far a cover lists a RAR or a tar, and what a partial listing leaves to correct (docs/spec.md section 4.4). */
object CoverListing {
    /** How many entries named as images the listing of a cover chosen automatically reads. */
    const val IMAGES = 5

    /**
     * The listing of a cover chosen automatically: up to the [IMAGES]th entry that goes into the pages by its name.
     * The charset is known only once the listing ends, and what the page rules read in a name (the separators, a
     * leading dot, `__MACOSX`, the extension) is ASCII, so the raw name is read as ISO-8859-1. A CP932 pair taken
     * for a separator can only leave an image uncounted, which reads on.
     */
    val automatic: ListingNeed = ListingNeed.Images(IMAGES) { entry -> counts(entry.rawName, entry.isDirectory) }

    private fun counts(rawName: ByteArray, isDirectory: Boolean) =
        pageCandidate(String(rawName, Charsets.ISO_8859_1), isDirectory) == PageCandidate.Image

    /**
     * The first of [pages], in natural order, that a listing for the first images counted towards the [IMAGES]: an
     * entry it took in by its first bytes was not counted (section 4.4). The first page when none was.
     */
    internal fun firstCounted(pages: List<PageEntry>): PageEntry? = pages.firstOrNull { counts(it.rawName, isDirectory = false) } ?: pages.firstOrNull()

    /**
     * The `entryIndex` of the provisional cover of [book], opened with [need]: the page the cover shows, of a listing
     * that stopped at the first images. `null` when the listing went to the end or stopped at a chosen page.
     */
    fun provisional(need: ListingNeed, book: OpenBook): Int? = provisional(need, book.partial, book.pages)

    internal fun provisional(need: ListingNeed, partial: Boolean, pages: List<PageEntry>): Int? =
        if (need is ListingNeed.Images && partial) coverPage(pages, coverEntry = null, listingFinal = true, counted = true)?.entryIndex else null
}
