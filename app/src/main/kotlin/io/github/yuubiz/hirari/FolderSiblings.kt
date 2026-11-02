package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.data.LocationRepository
import io.github.yuubiz.hirari.core.data.SettingsRepository
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.NaturalOrder
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.normalizePath
import io.github.yuubiz.hirari.feature.library.FileItem
import io.github.yuubiz.hirari.feature.library.FolderEntry
import io.github.yuubiz.hirari.feature.library.FolderLister
import io.github.yuubiz.hirari.feature.library.Listing
import io.github.yuubiz.hirari.feature.library.toItem
import io.github.yuubiz.hirari.feature.reader.BookEdge
import io.github.yuubiz.hirari.feature.reader.Sibling
import io.github.yuubiz.hirari.feature.reader.SiblingBooks
import kotlinx.coroutines.flow.first

/**
 * The reader's neighboring books, from the library's listing of the book's folder (docs/spec.md section 4.3). The
 * entries go in natural order with the folders first, whatever order the list shows. Archives, PDFs, and the
 * subfolders that hold an image count. A book of an SMB place has no neighbors until the library lists them.
 */
internal class FolderSiblings(
    private val folders: FolderLister,
    /** The tree of a SAF place, or `null` for a place that is gone or not a SAF folder. */
    private val treeOf: suspend (LocationId) -> UriString?,
    /** The `showDotFiles` setting, which the list follows. */
    private val dotFiles: suspend () -> Boolean,
) : SiblingBooks {
    constructor(locations: LocationRepository, folders: FolderLister, settings: SettingsRepository) : this(
        folders,
        treeOf = { (locations.get(it) as? LibraryLocation.SafTree)?.treeUri },
        dotFiles = { settings.settings.first().showDotFiles },
    )

    override suspend fun beside(book: BookAddress, edge: BookEdge): Sibling {
        if (book !is BookAddress.InLocation) return Sibling.NoFolder
        val tree = treeOf(book.location) ?: return Sibling.NoFolder
        val path = normalizePath(book.path)
        if (path.isEmpty()) return Sibling.NoFolder
        val folder = path.substringBeforeLast('/', "")
        val dotFiles = dotFiles()
        val entries = (folders.list(tree, folder, null) as? Listing.Listed)?.entries ?: return Sibling.NoFolder
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
                is FileItem.Folder -> if (holdsImage(tree, at, item.entry, dotFiles)) {
                    return Sibling.Found(BookAddress.InLocation(book.location, at, folder = true, item.entry.documentId))
                }
                else -> Unit
            }
        }
        return Sibling.Last
    }

    private suspend fun holdsImage(tree: UriString, path: String, entry: FolderEntry, dotFiles: Boolean): Boolean {
        val listed = folders.list(tree, path, entry.documentId) as? Listing.Listed ?: return false
        return listed.entries.any { it.toItem(dotFiles) is FileItem.Image }
    }
}
