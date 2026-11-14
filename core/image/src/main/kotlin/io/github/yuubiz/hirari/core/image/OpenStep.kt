package io.github.yuubiz.hirari.core.image

/**
 * What opening a book is doing (docs/spec.md section 4.8, 本を開いている間). [Books] reports the step before each
 * blocking stage, from the thread that stage runs on, so a reporter must be safe to call from any thread.
 */
sealed interface OpenStep {
    /** Finding the source, and reading the settings and records. */
    data object Preparing : OpenStep

    /** Connecting to an SMB server and opening the file. */
    data object Connecting : OpenStep

    /** Copying a document of a provider that cannot seek. [total] is null when the provider reports no size. */
    data class Copying(val bytes: Long, val total: Long?) : OpenStep

    /** Listing an archive. [fraction] is null when the header positions do not tell how far into the archive the walk is. */
    data class Listing(val entries: Int, val fraction: Float?) : OpenStep

    /** Listing the files of a folder, which is one query with no count on the way. */
    data object ListingFolder : OpenStep

    data object OpeningPdf : OpenStep
}
