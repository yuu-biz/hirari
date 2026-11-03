package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What a list needs in bulk to name the thumbnail of each of its items (docs/spec.md section 4.4), so that binding
 * a row asks the database nothing. Safe to call from the main thread.
 */
interface CoverIndex {
    /** The binding direction the user set for each book that has one; a book not in the map follows the global setting. */
    fun directions(): Flow<Map<BookId, ReadingDirection>>

    /** The cover chosen for each folder of [location] that has one, by the folder's path. */
    fun folderCovers(location: LocationId): Flow<Map<String, FolderCover>>
}

internal class RoomCoverIndex(database: HirariDatabase) : CoverIndex {
    private val overrides = database.bookOverrides()
    private val folders = database.folders()

    override fun directions(): Flow<Map<BookId, ReadingDirection>> = overrides.observeAll()
        .map { rows ->
            buildMap {
                for (row in rows) row.overrides.toBookOverrides().direction?.let { put(BookId(row.bookId), it) }
            }
        }
        .distinctUntilChanged()

    override fun folderCovers(location: LocationId): Flow<Map<String, FolderCover>> = folders.observeCovers(location.value)
        .map { rows -> rows.associate { it.path to FolderCover(it.imagePath, it.entry) } }
}
