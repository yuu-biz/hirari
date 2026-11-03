package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.data.LocationRepository
import io.github.yuubiz.hirari.core.data.SettingsRepository
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.NaturalOrder
import io.github.yuubiz.hirari.core.model.normalizePath
import io.github.yuubiz.hirari.feature.library.FileItem
import io.github.yuubiz.hirari.feature.library.FolderLister
import io.github.yuubiz.hirari.feature.library.Listing
import io.github.yuubiz.hirari.feature.library.SmbPlaces
import io.github.yuubiz.hirari.feature.library.toItem
import io.github.yuubiz.hirari.feature.text.TextBookRef
import io.github.yuubiz.hirari.feature.text.TextSibling
import io.github.yuubiz.hirari.feature.text.TextSiblings
import kotlinx.coroutines.flow.first

/**
 * The text reader's neighboring texts, from the library's listing of the text's folder (docs/spec.md sections 4.3
 * and 4.9). Only the texts of the folder count, in natural order by name: the folders, the archives and the images
 * are skipped, so the reading mode stays text. A text outside a place, and a text inside an archive, has none.
 */
internal class TextFolderSiblings(
    private val folders: PlaceFolders,
    /** The `showDotFiles` setting, which the list follows. */
    private val dotFiles: suspend () -> Boolean,
) : TextSiblings {
    constructor(locations: LocationRepository, saf: FolderLister, smb: SmbPlaces, settings: SettingsRepository) : this(
        placeFolders(locations::get, saf, smb),
        dotFiles = { settings.settings.first().showDotFiles },
    )

    override suspend fun beside(book: TextBookRef, edge: BookEdge): TextSibling {
        if (book !is TextBookRef.InLocation) return TextSibling.NoFolder
        val path = normalizePath(book.path)
        if (path.isEmpty()) return TextSibling.NoFolder
        val folder = path.substringBeforeLast('/', "")
        val entries = (folders.list(book.location, folder, null) as? Listing.Listed)?.entries ?: return TextSibling.NoFolder
        val dotFiles = dotFiles()
        val texts = entries.mapNotNull { it.toItem(dotFiles) as? FileItem.Text }.sortedWith(compareBy(NaturalOrder) { it.entry.name })
        val self = texts.indexOfFirst { it.entry.name == path.substringAfterLast('/') }
        if (self < 0) return TextSibling.NoFolder
        val next = texts.getOrNull(if (edge == BookEdge.End) self + 1 else self - 1) ?: return TextSibling.Last
        return TextSibling.Found(TextBookRef.InLocation(book.location, normalizePath("$folder/${next.entry.name}"), next.entry.documentId))
    }
}
