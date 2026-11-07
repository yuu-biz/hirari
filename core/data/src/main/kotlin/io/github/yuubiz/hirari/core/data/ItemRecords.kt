package io.github.yuubiz.hirari.core.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.withWriteTransaction
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.entryBookId
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.model.normalizePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Record changes after a successful storage operation (docs/spec.md sections 4.3 and 7). */
interface ItemRecords {
    /** [entryName] reads an entry's original name bytes from the archive at its new path. */
    suspend fun rename(place: LibraryLocation, oldPath: String, newPath: String, entryName: suspend (String, Int) -> ByteArray): Set<BookId>
    suspend fun delete(place: LibraryLocation, path: String): Set<BookId>
}

internal fun String.atOrUnder(root: String): Boolean = this == root || startsWith("$root/")

internal class RoomItemRecords(private val database: HirariDatabase) : ItemRecords {
    override suspend fun rename(place: LibraryLocation, oldPath: String, newPath: String, entryName: suspend (String, Int) -> ByteArray): Set<BookId> = withContext(Dispatchers.IO) {
        val old = normalizePath(oldPath)
        val new = normalizePath(newPath)
        require(old.isNotEmpty() && new.isNotEmpty())
        database.withWriteTransaction {
            val dao = database.itemRecords()
            val rows = dao.books(place.id.value, old)
            fun moved(path: String) = if (path.atOrUnder(old)) new + path.removePrefix(old) else path
            val ids = linkedMapOf(locationBookId(place, old).value to locationBookId(place, new).value)
            val changed = rows.map { row ->
                val path = moved(requireNotNull(row.path))
                val archive = if (row.textArchiveId != null) locationBookId(place, path) else null
                val id = if (archive != null) entryBookId(archive, entryName(path, requireNotNull(row.textEntryIndex))) else locationBookId(place, path)
                ids[row.bookId] = id.value
                if (archive != null) ids[requireNotNull(row.textArchiveId)] = archive.value
                row.copy(bookId = id.value, path = path, folder = path.substringBeforeLast('/', ""),
                    title = if (row.path == old && archive == null) path.substringAfterLast('/') else row.title,
                    textArchiveId = archive?.value)
            }
            // Include paths known only as pins, favorites or chosen folder covers.
            val favorites = dao.favorites(place.id.value, old)
            val pins = dao.pins(place.id.value, old)
            val covers = dao.covers(place.id.value, old)
            (favorites.map { it.path } + pins.map { it.path } + covers.flatMap { listOf(it.path, it.imagePath) })
                .filter { it.atOrUnder(old) }.forEach { ids[locationBookId(place, it).value] = locationBookId(place, moved(it)).value }
            for ((before, after) in ids) {
                if (before == after) continue
                dao.moveProgress(before, after)
                dao.moveHistory(before, after)
                dao.moveBookmarks(before, after)
                dao.moveOverrides(before, after)
            }
            rows.forEach { dao.deleteBook(it.bookId) }
            changed.forEach { database.books().upsert(it) }
            for (row in favorites.filter { it.path.atOrUnder(old) }) {
                database.folders().removeFavorite(row.locationId, row.path)
                database.folders().addFavorite(row.copy(path = moved(row.path), displayName = if (row.path == old) new.substringAfterLast('/') else row.displayName))
            }
            for (row in pins.filter { it.path.atOrUnder(old) }) dao.movePin(row.id, moved(row.path))
            for (row in covers.filter { it.path.atOrUnder(old) || it.imagePath.atOrUnder(old) }) {
                database.folders().clearCover(row.locationId, row.path)
                database.folders().setCover(row.copy(path = moved(row.path), imagePath = moved(row.imagePath)))
            }
            ids.keys.map(::BookId).toSet()
        }
    }

    override suspend fun delete(place: LibraryLocation, path: String): Set<BookId> = withContext(Dispatchers.IO) {
        val root = normalizePath(path)
        require(root.isNotEmpty())
        database.withWriteTransaction {
            val dao = database.itemRecords()
            val ids = linkedSetOf(locationBookId(place, root).value)
            dao.books(place.id.value, root).forEach {
                ids.add(it.bookId)
                it.textArchiveId?.let(ids::add)
            }
            val favorites = dao.favorites(place.id.value, root)
            val pins = dao.pins(place.id.value, root)
            val covers = dao.covers(place.id.value, root)
            (favorites.map { it.path } + pins.map { it.path } + covers.flatMap { listOf(it.path, it.imagePath) })
                .filter { it.atOrUnder(root) }.forEach { ids.add(locationBookId(place, it).value) }
            for (id in ids) {
                dao.deleteProgress(id)
                database.history().delete(id)
                database.bookmarks().deleteBooks(listOf(id))
                database.bookOverrides().delete(id)
                dao.deleteBook(id)
            }
            favorites.forEach { database.folders().removeFavorite(it.locationId, it.path) }
            pins.forEach { database.folders().unpin(it.id) }
            covers.forEach { database.folders().clearCover(it.locationId, it.path) }
            ids.map(::BookId).toSet()
        }
    }
}

@Dao
internal interface ItemRecordsDao {
    @Query("SELECT * FROM books WHERE location_id = :location AND (:root = '' OR path = :root OR substr(path, 1, length(:root) + 1) = :root || '/')")
    suspend fun books(location: Long, root: String = ""): List<BookRow>

    @Query("SELECT * FROM favorites WHERE location_id = :location AND (:root = '' OR path = :root OR substr(path, 1, length(:root) + 1) = :root || '/')")
    suspend fun favorites(location: Long, root: String = ""): List<FavoriteRow>

    @Query("SELECT * FROM pinned_folders WHERE location_id = :location AND (:root = '' OR path = :root OR substr(path, 1, length(:root) + 1) = :root || '/')")
    suspend fun pins(location: Long, root: String = ""): List<PinnedFolderRow>

    @Query("SELECT * FROM folder_covers WHERE location_id = :location AND (:root = '' OR path = :root OR substr(path, 1, length(:root) + 1) = :root || '/' OR image_path = :root OR substr(image_path, 1, length(:root) + 1) = :root || '/')")
    suspend fun covers(location: Long, root: String = ""): List<FolderCoverRow>

    @Query("UPDATE book_progress SET book_id = :after WHERE book_id = :before")
    suspend fun moveProgress(before: String, after: String)

    @Query("UPDATE history SET book_id = :after WHERE book_id = :before")
    suspend fun moveHistory(before: String, after: String)

    @Query("UPDATE bookmarks SET book_id = :after WHERE book_id = :before")
    suspend fun moveBookmarks(before: String, after: String)

    @Query("UPDATE book_overrides SET book_id = :after WHERE book_id = :before")
    suspend fun moveOverrides(before: String, after: String)

    @Query("UPDATE pinned_folders SET path = :path WHERE id = :id")
    suspend fun movePin(id: Long, path: String)

    @Query("DELETE FROM book_progress WHERE book_id = :id")
    suspend fun deleteProgress(id: String)

    @Query("DELETE FROM books WHERE book_id = :id")
    suspend fun deleteBook(id: String)
}
