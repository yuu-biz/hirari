package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.normalizePath

/**
 * A home screen shortcut to a folder or a book of a place (docs/spec.md section 4.11). It holds the place's id and
 * the path, never a credential. [label] names it on the home screen.
 */
data class PlaceShortcut(val location: LocationId, val path: String, val folder: Boolean, val label: String)

/** Where a shortcut leads once its place and path are looked up. */
sealed interface ShortcutTarget {
    data class Folder(val key: FolderKey) : ShortcutTarget

    data class Book(val request: OpenBookRequest) : ShortcutTarget

    data class Text(val address: TextAddress) : ShortcutTarget

    /** The place is gone or lost its grant, or the path is no longer in it. */
    data object Missing : ShortcutTarget
}

/**
 * Looks up the place and the path of [shortcut]. A book is looked for in the listing of its folder, which also
 * gives a SAF book its document. A SAF folder the provider refuses counts as gone, since the grant can be lost
 * while the place still says it holds it. A folder that does not list for another reason still opens, so its file
 * list shows why.
 */
suspend fun LibraryServices.resolve(shortcut: PlaceShortcut): ShortcutTarget {
    val place = locations.get(shortcut.location)?.takeIf { it.usable() } ?: return ShortcutTarget.Missing
    val path = normalizePath(shortcut.path)
    if (shortcut.folder) {
        return when (val listed = list(place, path)) {
            is Listing.Listed -> ShortcutTarget.Folder(FolderKey(shortcut.location, path, listed.folderDocumentId))
            is Listing.Failed -> when (listed.reason) {
                ListFailure.NotFound, ListFailure.NoPermission -> ShortcutTarget.Missing
                else -> ShortcutTarget.Folder(FolderKey(shortcut.location, path))
            }
        }
    }
    val parent = path.substringBeforeLast('/', "")
    val name = path.substringAfterLast('/')
    val entry = (list(place, parent) as? Listing.Listed)?.entries?.firstOrNull { !it.directory && normalizePath(it.name) == name }
    val item = entry?.toItem(showDotFiles = true) ?: return ShortcutTarget.Missing
    return when {
        item is FileItem.Text -> ShortcutTarget.Text(TextAddress.InLocation(shortcut.location, path, entry.documentId))
        item is FileItem.Archive -> ShortcutTarget.Book(OpenBookRequest(BookAddress.InLocation(shortcut.location, path, folder = false, entry.documentId)))
        else -> ShortcutTarget.Missing
    }
}

private suspend fun LibraryServices.list(place: LibraryLocation, path: String): Listing = when (place) {
    is LibraryLocation.SafTree -> folders.list(place.treeUri, path, null)
    is LibraryLocation.SmbShare -> smb.list(place, path)
}

/**
 * The shortcut the long-press sheet offers for [row] of a folder of the place [location], or null when the row has
 * none: a folder, a book or a text, of a place of either kind (docs/spec.md section 4.11).
 */
fun shortcutFor(row: FileRow, location: LocationId): PlaceShortcut? = when (row.item) {
    is FileItem.Folder -> PlaceShortcut(location, row.path, folder = true, row.item.entry.name)
    is FileItem.Archive, is FileItem.Text -> PlaceShortcut(location, row.path, folder = false, row.item.entry.name)
    is FileItem.Image -> null
}
