package io.github.yuubiz.hirari.core.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import io.github.yuubiz.hirari.core.model.BookId

/**
 * The provisional covers (docs/spec.md section 4.4): books whose cover was chosen automatically from a listing that
 * stopped at the first images, with the `entryIndex` it shows, until the book is listed whole.
 */
interface ProvisionalCovers {
    suspend fun remember(book: BookId, entryIndex: Int)

    /** The `entryIndex` remembered for [book], or `null` when its cover is not provisional. */
    suspend fun entry(book: BookId): Int?

    suspend fun forget(book: BookId)

    companion object {
        val None: ProvisionalCovers = object : ProvisionalCovers {
            override suspend fun remember(book: BookId, entryIndex: Int) = Unit

            override suspend fun entry(book: BookId): Int? = null

            override suspend fun forget(book: BookId) = Unit
        }
    }
}

@Entity(tableName = "provisional_covers")
internal data class ProvisionalCoverRow(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "entry_index") val entryIndex: Int,
)

@Dao
internal interface ProvisionalCoverDao {
    @Upsert
    suspend fun upsert(row: ProvisionalCoverRow)

    @Query("SELECT entry_index FROM provisional_covers WHERE book_id = :book")
    suspend fun entry(book: String): Int?

    @Query("DELETE FROM provisional_covers WHERE book_id = :book")
    suspend fun delete(book: String)
}

internal class RoomProvisionalCovers(private val dao: ProvisionalCoverDao) : ProvisionalCovers {
    override suspend fun remember(book: BookId, entryIndex: Int) = dao.upsert(ProvisionalCoverRow(book.value, entryIndex))

    override suspend fun entry(book: BookId): Int? = dao.entry(book.value)

    override suspend fun forget(book: BookId) = dao.delete(book.value)
}
