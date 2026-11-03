package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.FolderCover
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.locationBookId
import kotlinx.coroutines.flow.first

/** The binding direction a book is shown with: its own, else the global one (docs/spec.md section 4.4). */
internal suspend fun LibraryServices.directionOf(id: BookId): ReadingDirection =
    coverIndex.directions().first()[id] ?: settings.settings.first().readingDirection

/**
 * 子の画像やページをフォルダの表紙にする / 選ぶのをやめる (docs/spec.md section 4.4): saves the choice for the
 * folder at [folder] of [place], then makes the thumbnail of that folder again at every size. When the folder's own
 * entry cannot be found, only the choice is saved; the folder shows it once its listing is read again.
 */
internal suspend fun LibraryServices.applyFolderCover(place: LibraryLocation, folder: String, cover: FolderCover?) {
    locations.setFolderCover(place.id, folder, cover)
    val direction = directionOf(locationBookId(place, folder))
    val spec = covers.folderSpec(place, folder, direction, cover?.let { covers.chosen(place, it) }) ?: return
    covers.regenerate(spec, covers.sizesFrom(settings.settings.first().thumbSize))
}
