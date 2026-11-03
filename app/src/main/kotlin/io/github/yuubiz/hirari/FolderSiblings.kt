package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.data.LocationRepository
import io.github.yuubiz.hirari.core.data.SettingsRepository
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.NaturalOrder
import io.github.yuubiz.hirari.core.model.Sibling
import io.github.yuubiz.hirari.core.model.Siblings
import io.github.yuubiz.hirari.core.model.normalizePath
import io.github.yuubiz.hirari.feature.library.FileItem
import io.github.yuubiz.hirari.feature.library.FolderEntry
import io.github.yuubiz.hirari.feature.library.FolderLister
import io.github.yuubiz.hirari.feature.library.Listing
import io.github.yuubiz.hirari.feature.library.SmbPlaces
import io.github.yuubiz.hirari.feature.library.toItem
import kotlinx.coroutines.flow.first

/** The listing of the folder at [path] of the place [location], as the file list reads it. */
internal fun interface PlaceFolders {
    /** `null` when the place is gone. A known [documentId] saves a SAF place looking the path up. */
    suspend fun list(location: LocationId, path: String, documentId: String?): Listing?
}

/**
 * The folders of SAF places through [saf], and of SMB places through [smb], which shares the server of the place
 * with the rest of the app (docs/spec.md section 3.1).
 */
internal fun placeFolders(place: suspend (LocationId) -> LibraryLocation?, saf: FolderLister, smb: SmbPlaces) =
    PlaceFolders { location, path, documentId ->
        when (val found = place(location)) {
            is LibraryLocation.SafTree -> saf.list(found.treeUri, path, documentId)
            is LibraryLocation.SmbShare -> smb.list(found, path)
            null -> null
        }
    }

/**
 * The reader's neighboring books, from the library's listing of the book's folder (docs/spec.md section 4.3). The
 * entries go in natural order with the folders first, whatever order the list shows. Archives, PDFs, and the
 * subfolders that hold an image count.
 */
internal class FolderSiblings(
    private val folders: PlaceFolders,
    /** The `showDotFiles` setting, which the list follows. */
    private val dotFiles: suspend () -> Boolean,
) : Siblings<BookAddress, BookAddress> {
    constructor(locations: LocationRepository, saf: FolderLister, smb: SmbPlaces, settings: SettingsRepository) : this(
        placeFolders(locations::get, saf, smb),
        dotFiles = { settings.settings.first().showDotFiles },
    )

    override suspend fun beside(book: BookAddress, edge: BookEdge): Sibling<BookAddress> {
        if (book !is BookAddress.InLocation) return Sibling.NoFolder
        val path = normalizePath(book.path)
        if (path.isEmpty()) return Sibling.NoFolder
        val folder = path.substringBeforeLast('/', "")
        val dotFiles = dotFiles()
        val entries = (folders.list(book.location, folder, null) as? Listing.Listed)?.entries ?: return Sibling.NoFolder
        val items = entries.mapNotNull { it.toItem(dotFiles) }
            .filter { it is FileItem.Archive || it is FileItem.Folder }
            .sortedWith(compareBy<FileItem> { it !is FileItem.Folder }.then(compareBy(NaturalOrder) { it.entry.name }))
        val self = items.indexOfFirst { it.entry.name == path.substringAfterLast('/') }
        if (self < 0) return Sibling.NoFolder
        val toward = if (edge == BookEdge.End) items.drop(self + 1) else items.take(self).asReversed()
        for (item in toward) {
            val at = normalizePath("$folder/${item.entry.name}")
            when (item) {
                is FileItem.Archive -> return Sibling.Found(BookAddress.InLocation(book.location, at, folder = false, item.entry.documentId))
                is FileItem.Folder -> if (holdsImage(book.location, at, item.entry, dotFiles)) {
                    return Sibling.Found(BookAddress.InLocation(book.location, at, folder = true, item.entry.documentId))
                }
                else -> Unit
            }
        }
        return Sibling.Last
    }

    private suspend fun holdsImage(location: LocationId, path: String, entry: FolderEntry, dotFiles: Boolean): Boolean {
        val listed = folders.list(location, path, entry.documentId) as? Listing.Listed ?: return false
        return listed.entries.any { it.toItem(dotFiles) is FileItem.Image }
    }
}
