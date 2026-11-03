package io.github.yuubiz.hirari.core.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.CredentialRef
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.normalizePath
import kotlinx.coroutines.flow.Flow
import java.nio.charset.Charset
import java.time.Instant

internal enum class LocationKind { Saf, Smb }

/** One row per place; the columns of the other kind are `null`. No secret is stored here (docs/spec.md section 7). */
@Entity(tableName = "locations")
internal data class LocationRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: LocationKind,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "tree_uri") val treeUri: String? = null,
    @ColumnInfo(name = "permission_held") val permissionHeld: Boolean = false,
    val host: String? = null,
    val port: Int = 0,
    val share: String? = null,
    @ColumnInfo(name = "base_path") val basePath: String? = null,
    val domain: String? = null,
    val user: String? = null,
    @ColumnInfo(name = "credential_alias") val credentialAlias: String? = null,
)

internal fun LocationRow.toModel(): LibraryLocation = when (kind) {
    LocationKind.Saf -> LibraryLocation.SafTree(LocationId(id), displayName, UriString(treeUri!!), permissionHeld)
    LocationKind.Smb -> LibraryLocation.SmbShare(
        id = LocationId(id),
        displayName = displayName,
        host = host!!,
        port = port,
        share = share!!,
        basePath = basePath.orEmpty(),
        domain = domain,
        user = user,
        credential = credentialAlias?.let(::CredentialRef),
    )
}

@Dao
internal interface LocationDao {
    @Query("SELECT * FROM locations ORDER BY id")
    fun observeAll(): Flow<List<LocationRow>>

    @Query("SELECT * FROM locations ORDER BY id")
    suspend fun all(): List<LocationRow>

    @Query("SELECT * FROM locations WHERE id = :id")
    suspend fun get(id: Long): LocationRow?

    @Insert
    suspend fun insert(row: LocationRow): Long

    @Update
    suspend fun update(row: LocationRow)

    @Query("UPDATE locations SET display_name = :displayName WHERE id = :id")
    suspend fun rename(id: Long, displayName: String)

    @Query("DELETE FROM locations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE locations SET permission_held = (tree_uri IN (:granted)) WHERE kind = 'Saf'")
    suspend fun setGrants(granted: List<String>)
}

/** What a book is stored as: the container of an image book, or [Text]. */
internal enum class StoredFormat { Zip, Rar, SevenZip, Tar, Folder, Pdf, Text }

/**
 * The index of books the app has opened (docs/spec.md section 7). [folder] is the parent of [path] inside the
 * location, `""` at its top, so a folder's books are one indexed lookup.
 */
@Entity(tableName = "books", indices = [Index("location_id", "folder")])
internal data class BookRow(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "location_id") val locationId: Long?,
    val path: String?,
    val folder: String?,
    val uri: String?,
    val format: StoredFormat,
    /** The charset name of a text book, `null` to detect it. */
    val charset: String?,
    val title: String,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    val modified: Long,
    @ColumnInfo(name = "page_count") val pageCount: Int?,
    /** The `entryIndex` of the page the user chose as the cover. */
    @ColumnInfo(name = "cover_entry") val coverEntry: Int?,
)

internal fun Book.toRow(coverEntry: Int?): BookRow {
    val source = source
    val path = (source as? BookSource.InLocation)?.path?.let(::normalizePath)
    val format = when (val f = format) {
        is BookFormat.Images -> StoredFormat.valueOf(f.container.name)
        is BookFormat.Text -> StoredFormat.Text
    }
    return BookRow(
        bookId = id.value,
        locationId = (source as? BookSource.InLocation)?.location?.value,
        path = path,
        folder = path?.substringBeforeLast('/', missingDelimiterValue = ""),
        uri = (source as? BookSource.External)?.uri?.value,
        format = format,
        charset = (this.format as? BookFormat.Text)?.charset?.name(),
        title = title,
        byteSize = byteSize,
        modified = modified.toEpochMilli(),
        pageCount = pageCount,
        coverEntry = coverEntry,
    )
}

internal fun BookRow.toModel(): Book = Book(
    id = BookId(bookId),
    source = if (locationId != null) BookSource.InLocation(LocationId(locationId), path.orEmpty()) else BookSource.External(UriString(uri.orEmpty())),
    format = when (format) {
        StoredFormat.Text -> BookFormat.Text(charset?.let { runCatching { Charset.forName(it) }.getOrNull() })
        else -> BookFormat.Images(Container.valueOf(format.name))
    },
    title = title,
    byteSize = byteSize,
    modified = Instant.ofEpochMilli(modified),
    pageCount = pageCount,
)

internal data class BookWithProgress(
    @Embedded val book: BookRow,
    @Relation(parentColumns = ["book_id"], entityColumns = ["book_id"]) val progress: BookProgressRow?,
)

@Dao
internal interface BookDao {
    @Query("SELECT * FROM books WHERE book_id = :bookId")
    suspend fun get(bookId: String): BookRow?

    @Upsert
    suspend fun upsert(row: BookRow)

    /** Keeps the page count and the cover that [row] does not know. */
    @Transaction
    suspend fun record(row: BookRow) {
        val known = get(row.bookId)
        upsert(row.copy(pageCount = row.pageCount ?: known?.pageCount, coverEntry = known?.coverEntry))
    }

    @Query("UPDATE books SET cover_entry = :entry WHERE book_id = :bookId")
    suspend fun setCover(bookId: String, entry: Int?)

    @Transaction
    @Query("SELECT * FROM books WHERE location_id = :locationId AND folder = :folder")
    fun observeFolder(locationId: Long, folder: String): Flow<List<BookWithProgress>>

    @Transaction
    @Query(
        """SELECT books.* FROM books JOIN book_progress ON book_progress.book_id = books.book_id
        WHERE book_progress.state = 'Reading' ORDER BY book_progress.updated_at DESC LIMIT :limit""",
    )
    fun observeReading(limit: Int): Flow<List<BookWithProgress>>
}

/** One row per book, at the last time it was opened (docs/spec.md section 2.8). */
@Entity(tableName = "history", indices = [Index("opened_at")])
internal data class HistoryRow(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
)

internal data class HistoryWithBook(
    @Embedded val history: HistoryRow,
    @Relation(parentColumns = ["book_id"], entityColumns = ["book_id"]) val book: BookRow?,
    @Relation(parentColumns = ["book_id"], entityColumns = ["book_id"]) val progress: BookProgressRow?,
)

@Dao
internal interface HistoryDao {
    @Transaction
    @Query("SELECT * FROM history ORDER BY opened_at DESC")
    fun observe(): Flow<List<HistoryWithBook>>

    @Query("SELECT * FROM history ORDER BY opened_at DESC")
    suspend fun all(): List<HistoryRow>

    @Query("SELECT * FROM history WHERE book_id = :bookId")
    suspend fun get(bookId: String): HistoryRow?

    @Upsert
    suspend fun upsert(rows: List<HistoryRow>)

    @Query("DELETE FROM history WHERE book_id NOT IN (SELECT book_id FROM history ORDER BY opened_at DESC LIMIT :limit)")
    suspend fun keepNewest(limit: Int)

    /** Writes [row] unless the book already has a time as new or newer, so a late write of an older session loses. */
    @Transaction
    suspend fun record(row: HistoryRow, limit: Int) {
        if (get(row.bookId)?.let { it.openedAt >= row.openedAt } != true) upsert(listOf(row))
        keepNewest(limit)
    }

    @Query("DELETE FROM history WHERE book_id = :bookId")
    suspend fun delete(bookId: String)

    @Query("DELETE FROM history")
    suspend fun deleteAll()
}

@Entity(tableName = "bookmarks", indices = [Index("book_id")])
internal data class BookmarkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "position_kind") val positionKind: PositionKind,
    @ColumnInfo(name = "position_at") val positionAt: Long,
    @ColumnInfo(name = "position_total") val positionTotal: Long,
    val label: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

internal data class BookmarkWithBook(
    @Embedded val bookmark: BookmarkRow,
    @Relation(parentColumns = ["book_id"], entityColumns = ["book_id"]) val book: BookRow?,
)

@Dao
internal interface BookmarkDao {
    @Transaction
    @Query("SELECT * FROM bookmarks")
    fun observe(): Flow<List<BookmarkWithBook>>

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY position_at, created_at")
    fun observeBook(bookId: String): Flow<List<BookmarkRow>>

    @Query("SELECT * FROM bookmarks ORDER BY id")
    suspend fun all(): List<BookmarkRow>

    @Insert
    suspend fun insert(row: BookmarkRow): Long

    @Insert
    suspend fun insert(rows: List<BookmarkRow>)

    @Query("UPDATE bookmarks SET label = :label WHERE id = :id")
    suspend fun rename(id: Long, label: String)

    @Query("DELETE FROM bookmarks WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAll()

    @Query("DELETE FROM bookmarks WHERE book_id IN (:bookIds)")
    suspend fun deleteBooks(bookIds: List<String>)

    @Query(
        """DELETE FROM bookmarks WHERE book_id IN (SELECT book_id FROM books WHERE location_id = :locationId
        AND (:folder = '' OR folder = :folder OR substr(folder, 1, length(:folder) + 1) = :folder || '/'))""",
    )
    suspend fun deleteUnder(locationId: Long, folder: String)

    @Query(
        """DELETE FROM bookmarks WHERE book_id NOT IN (SELECT books.book_id FROM books
        JOIN locations ON locations.id = books.location_id WHERE locations.kind = 'Smb')""",
    )
    suspend fun deleteOnDevice()
}

/** [overrides] is the JSON of [BookOverridesJson], read key by key like the settings. */
@Entity(tableName = "book_overrides")
internal data class BookOverridesRow(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    val overrides: String,
)

@Dao
internal interface BookOverridesDao {
    @Query("SELECT * FROM book_overrides WHERE book_id = :bookId")
    fun observe(bookId: String): Flow<BookOverridesRow?>

    @Query("SELECT * FROM book_overrides")
    suspend fun all(): List<BookOverridesRow>

    @Query("SELECT * FROM book_overrides")
    fun observeAll(): Flow<List<BookOverridesRow>>

    @Upsert
    suspend fun upsert(rows: List<BookOverridesRow>)

    @Query("DELETE FROM book_overrides WHERE book_id = :bookId")
    suspend fun delete(bookId: String)
}

@Entity(tableName = "pinned_folders", indices = [Index("location_id", "path", unique = true)])
internal data class PinnedFolderRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "location_id") val locationId: Long,
    val path: String,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long,
)

/**
 * The image a user chose as a folder's thumbnail (docs/spec.md section 4.4): an image file, or the page with
 * [entry] as its `entryIndex` in the book at [imagePath].
 */
@Entity(tableName = "folder_covers", primaryKeys = ["location_id", "path"])
internal data class FolderCoverRow(
    @ColumnInfo(name = "location_id") val locationId: Long,
    val path: String,
    @ColumnInfo(name = "image_path") val imagePath: String,
    val entry: Int?,
)

@Dao
internal interface FolderDao {
    @Query("SELECT * FROM pinned_folders ORDER BY pinned_at")
    fun observePinned(): Flow<List<PinnedFolderRow>>

    @Insert(onConflict = androidx.room3.OnConflictStrategy.IGNORE)
    suspend fun pin(row: PinnedFolderRow)

    @Query("DELETE FROM pinned_folders WHERE id = :id")
    suspend fun unpin(id: Long)

    @Query("SELECT * FROM folder_covers WHERE location_id = :locationId AND path = :path")
    fun observeCover(locationId: Long, path: String): Flow<FolderCoverRow?>

    @Query("SELECT * FROM folder_covers WHERE location_id = :locationId")
    fun observeCovers(locationId: Long): Flow<List<FolderCoverRow>>

    @Upsert
    suspend fun setCover(row: FolderCoverRow)

    @Query("DELETE FROM folder_covers WHERE location_id = :locationId AND path = :path")
    suspend fun clearCover(locationId: Long, path: String)

    @Query("DELETE FROM pinned_folders WHERE location_id = :locationId")
    suspend fun unpinLocation(locationId: Long)

    @Query("DELETE FROM folder_covers WHERE location_id = :locationId")
    suspend fun clearLocationCovers(locationId: Long)
}
