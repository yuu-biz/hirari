package io.github.yuubiz.hirari.core.archive

/**
 * How far [ArchiveSession.open] lists an archive (docs/spec.md section 4.4). RAR and tar keep a header before each
 * entry, spread over the whole archive, so listing to the end reads once per entry, while a cover needs only the
 * first few. ZIP and 7z read their index in one place and are listed to the end whatever is asked.
 */
sealed interface ListingNeed {
    /** Every entry, as the reader and the archive list need. */
    data object All : ListingNeed

    /** Up to the [count]th entry, in archive order, that [isImage] accepts. */
    class Images(val count: Int, val isImage: (ArchiveEntry) -> Boolean) : ListingNeed {
        init {
            require(count > 0) { "count $count" }
        }
    }

    /** Up to the entry at [entryIndex]. */
    data class UpTo(val entryIndex: Int) : ListingNeed
}
