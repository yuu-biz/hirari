package io.github.yuubiz.hirari.core.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.withWriteTransaction
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.entryBookId
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.model.normalizePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChangedFolderCover(val place: LibraryLocation, val path: String)

data class ItemRecordChanges(
    val oldIds: Set<BookId>,
    val skipped: Int = 0,
    val folderCovers: Set<ChangedFolderCover> = emptySet(),
)

/** Record changes after storage has changed (docs/spec.md sections 2.2, 4.3 and 7). */
interface ItemRecords {
    /** Reads original entry name bytes at the new path, before taking the write transaction. */
    suspend fun rename(place: LibraryLocation, oldPath: String, newPath: String, entryName: suspend (String, Int) -> ByteArray): ItemRecordChanges
    suspend fun delete(place: LibraryLocation, path: String): ItemRecordChanges
}

internal fun String.atOrUnder(root: String): Boolean = root.isEmpty() || this == root || startsWith("$root/")

private fun LibraryLocation.absolute(path: String): String = normalizePath(when (this) {
    is LibraryLocation.SafTree -> path
    is LibraryLocation.SmbShare -> "$basePath/$path"
})

private fun LibraryLocation.relative(path: String): String? {
    val base = if (this is LibraryLocation.SmbShare) normalizePath(basePath) else ""
    return if (!path.atOrUnder(base)) null else if (base.isEmpty()) path else path.removePrefix(base).removePrefix("/")
}

private fun LibraryLocation.sharesIds(other: LibraryLocation): Boolean = when {
    this is LibraryLocation.SafTree && other is LibraryLocation.SafTree -> treeUri == other.treeUri
    this is LibraryLocation.SmbShare && other is LibraryLocation.SmbShare ->
        host.equals(other.host, true) && port == other.port && share.equals(other.share, true)
    else -> false
}

internal class RoomItemRecords(private val database: HirariDatabase) : ItemRecords {
    private suspend fun places(place: LibraryLocation): List<LibraryLocation> =
        (database.locations().all().map { it.toModel() } + place).distinctBy { it.id }.filter { place.sharesIds(it) }

    private suspend fun rows(places: List<LibraryLocation>, root: String): List<Pair<LibraryLocation, BookRow>> =
        places.flatMap { location -> database.itemRecords().books(location.id.value).filter {
            it.path?.let(location::absolute)?.atOrUnder(root) == true
        }.map { location to it } }

    private suspend fun erase(id: String) {
        database.itemRecords().deleteProgress(id)
        database.history().delete(id)
        database.bookmarks().deleteBooks(listOf(id))
        database.bookOverrides().delete(id)
        database.itemRecords().deleteBook(id)
    }

    override suspend fun rename(place: LibraryLocation, oldPath: String, newPath: String, entryName: suspend (String, Int) -> ByteArray): ItemRecordChanges = withContext(Dispatchers.IO) {
        val old = place.absolute(oldPath)
        val new = place.absolute(newPath)
        require(normalizePath(oldPath).isNotEmpty() && normalizePath(newPath).isNotEmpty())
        fun moved(path: String) = if (path.atOrUnder(old)) new + path.removePrefix(old) else path
        fun destination(location: LibraryLocation): LibraryLocation =
            if (location is LibraryLocation.SmbShare) location.copy(basePath = moved(normalizePath(location.basePath))) else location
        // No archive or provider I/O while the database holds its write transaction.
        val names = mutableMapOf<BookRow, ByteArray>()
        for ((location, row) in rows(places(place), old)) {
            if (row.textArchiveId == null) continue
            val path = requireNotNull(place.relative(moved(location.absolute(requireNotNull(row.path)))))
            try {
                names[row] = entryName(path, requireNotNull(row.textEntryIndex))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Leave this entry under its old id and report it, while moving the other records.
            }
        }
        database.withWriteTransaction {
            val dao = database.itemRecords()
            val locations = places(place)
            val ids = linkedMapOf(locationBookId(place, oldPath).value to locationBookId(place, newPath).value)
            val changed = mutableListOf<BookRow>()
            var skipped = 0
            for ((location, row) in rows(locations, old)) {
                val raw = names[row]
                if (row.textArchiveId != null && raw == null) { skipped++; continue }
                val target = destination(location)
                val oldAbsolute = location.absolute(requireNotNull(row.path))
                val path = requireNotNull(target.relative(moved(oldAbsolute)))
                val archive = if (raw != null) locationBookId(target, path) else null
                val id = if (archive != null) entryBookId(archive, requireNotNull(raw)) else locationBookId(target, path)
                ids[row.bookId] = id.value
                if (archive != null) ids[requireNotNull(row.textArchiveId)] = archive.value
                changed += row.copy(bookId = id.value, path = path, folder = path.substringBeforeLast('/', ""),
                    title = if (oldAbsolute == old && archive == null) path.substringAfterLast('/') else row.title,
                    textArchiveId = archive?.value)
            }
            val coverIds = linkedSetOf<BookId>()
            val folderCovers = linkedSetOf<ChangedFolderCover>()
            for (location in locations) {
                val target = destination(location)
                fun movedLocal(path: String) = requireNotNull(target.relative(moved(location.absolute(path))))
                if (target != location) database.locations().get(location.id.value)?.let {
                    database.locations().update(it.copy(basePath = (target as LibraryLocation.SmbShare).basePath))
                }
                val favorites = dao.favorites(location.id.value).filter { location.absolute(it.path).atOrUnder(old) }
                val pins = dao.pins(location.id.value).filter { location.absolute(it.path).atOrUnder(old) }
                val covers = dao.covers(location.id.value).filter { location.absolute(it.path).atOrUnder(old) || location.absolute(it.imagePath).atOrUnder(old) }
                (favorites.map { it.path } + pins.map { it.path } + covers.flatMap { listOf(it.path, it.imagePath) })
                    .filter { location.absolute(it).atOrUnder(old) }.forEach {
                        ids[locationBookId(location, it).value] = locationBookId(target, movedLocal(it)).value
                    }
                favorites.forEach { row ->
                    database.folders().removeFavorite(row.locationId, row.path)
                    database.folders().removeFavorite(row.locationId, movedLocal(row.path))
                    database.folders().addFavorite(row.copy(path = movedLocal(row.path), displayName = if (location.absolute(row.path) == old) new.substringAfterLast('/') else row.displayName))
                }
                pins.forEach {
                    val after = movedLocal(it.path)
                    if (after != it.path) {
                        dao.deletePinAt(it.locationId, after)
                        dao.movePin(it.id, after)
                    }
                }
                covers.forEach { row ->
                    coverIds += locationBookId(location, row.path)
                    folderCovers += ChangedFolderCover(target, movedLocal(row.path))
                    database.folders().clearCover(row.locationId, row.path)
                    database.folders().setCover(row.copy(path = movedLocal(row.path), imagePath = movedLocal(row.imagePath)))
                }
            }
            for ((before, after) in ids) {
                if (before == after) continue
                erase(after)
                dao.moveProgress(before, after)
                dao.moveHistory(before, after)
                dao.moveBookmarks(before, after)
                dao.moveOverrides(before, after)
                dao.deleteBook(before)
            }
            changed.forEach { database.books().upsert(it) }
            ItemRecordChanges((ids.keys + ids.values).map(::BookId).toSet() + coverIds, skipped, folderCovers)
        }
    }

    override suspend fun delete(place: LibraryLocation, path: String): ItemRecordChanges = withContext(Dispatchers.IO) {
        val root = place.absolute(path)
        require(normalizePath(path).isNotEmpty())
        database.withWriteTransaction {
            val dao = database.itemRecords()
            val locations = places(place)
            val ids = linkedSetOf(locationBookId(place, path).value)
            rows(locations, root).forEach { (_, row) -> ids += row.bookId; row.textArchiveId?.let(ids::add) }
            val coverIds = linkedSetOf<BookId>()
            val folderCovers = linkedSetOf<ChangedFolderCover>()
            for (location in locations) {
                val favorites = dao.favorites(location.id.value).filter { location.absolute(it.path).atOrUnder(root) }
                val pins = dao.pins(location.id.value).filter { location.absolute(it.path).atOrUnder(root) }
                val covers = dao.covers(location.id.value).filter { location.absolute(it.path).atOrUnder(root) || location.absolute(it.imagePath).atOrUnder(root) }
                (favorites.map { it.path } + pins.map { it.path } + covers.flatMap { listOf(it.path, it.imagePath) })
                    .filter { location.absolute(it).atOrUnder(root) }.forEach { ids += locationBookId(location, it).value }
                favorites.forEach { database.folders().removeFavorite(it.locationId, it.path) }
                pins.forEach { database.folders().unpin(it.id) }
                covers.forEach {
                    coverIds += locationBookId(location, it.path)
                    if (!location.absolute(it.path).atOrUnder(root)) folderCovers += ChangedFolderCover(location, it.path)
                    database.folders().clearCover(it.locationId, it.path)
                }
            }
            ids.forEach { erase(it) }
            ItemRecordChanges(ids.map(::BookId).toSet() + coverIds, folderCovers = folderCovers)
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

    @Query("DELETE FROM pinned_folders WHERE location_id = :location AND path = :path")
    suspend fun deletePinAt(location: Long, path: String)

    @Query("UPDATE pinned_folders SET path = :path WHERE id = :id")
    suspend fun movePin(id: Long, path: String)

    @Query("DELETE FROM book_progress WHERE book_id = :id")
    suspend fun deleteProgress(id: String)

    @Query("DELETE FROM books WHERE book_id = :id")
    suspend fun deleteBook(id: String)
}
