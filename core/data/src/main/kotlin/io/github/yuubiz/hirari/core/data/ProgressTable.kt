package io.github.yuubiz.hirari.core.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import java.time.Instant

internal enum class PositionKind { Image, Text }

/**
 * [positionAt] and [positionTotal] are 0 when [positionKind] is `null`. [positionOffset] is how far down its page an
 * image position in 縦に連続 is, as a fraction of the page's height, and 0 otherwise (docs/spec.md section 2.7).
 */
@Entity(tableName = "book_progress")
internal data class BookProgressRow(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    val state: ReadState,
    @ColumnInfo(name = "position_kind") val positionKind: PositionKind?,
    @ColumnInfo(name = "position_at") val positionAt: Long,
    @ColumnInfo(name = "position_total") val positionTotal: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "position_offset", defaultValue = "0") val positionOffset: Float = 0f,
)

@Dao
internal interface BookProgressDao {
    @Query("SELECT * FROM book_progress WHERE book_id = :bookId")
    suspend fun get(bookId: String): BookProgressRow?

    @Query("SELECT * FROM book_progress WHERE book_id = :bookId")
    fun observe(bookId: String): Flow<BookProgressRow?>

    @Query("SELECT * FROM book_progress WHERE book_id IN (:bookIds)")
    fun observe(bookIds: List<String>): Flow<List<BookProgressRow>>

    @Query("SELECT * FROM book_progress")
    suspend fun all(): List<BookProgressRow>

    @Upsert
    suspend fun upsert(rows: List<BookProgressRow>)

    /** Stores each row of [rows] that is newer than the stored progress of its book. */
    @Transaction
    suspend fun upsertNewer(rows: List<BookProgressRow>) {
        upsert(rows.newestPerBook().filter { row -> get(row.bookId).let { it == null || it.updatedAt < row.updatedAt } })
    }
}

/** The row with the latest [BookProgressRow.updatedAt] of each book, so that no older row of a book is stored after a newer one. */
internal fun List<BookProgressRow>.newestPerBook(): List<BookProgressRow> =
    groupBy { it.bookId }.values.map { rows -> rows.reduce { newest, row -> if (row.updatedAt > newest.updatedAt) row else newest } }

internal fun ReadingPosition?.columns(): Triple<PositionKind?, Long, Long> = when (this) {
    null -> Triple(null, 0L, 0L)
    is ReadingPosition.Image -> Triple(PositionKind.Image, viewStart.toLong(), pageCount.toLong())
    is ReadingPosition.Text -> Triple(PositionKind.Text, charOffset, totalChars)
}

internal fun positionOf(kind: PositionKind?, at: Long, total: Long): ReadingPosition? = when (kind) {
    null -> null
    PositionKind.Image -> ReadingPosition.Image(at.toInt(), total.toInt())
    PositionKind.Text -> ReadingPosition.Text(at, total)
}

internal fun BookProgress.toRow(): BookProgressRow {
    val (kind, at, total) = position.columns()
    val offset = (position as? ReadingPosition.Image)?.offset ?: 0f
    return BookProgressRow(book.value, state, kind, at, total, updatedAt.toEpochMilli(), offset)
}

internal fun BookProgressRow.toModel(): BookProgress = BookProgress(
    book = BookId(bookId),
    state = state,
    position = positionOf(positionKind, positionAt, positionTotal).let { if (it is ReadingPosition.Image) it.copy(offset = positionOffset.coerceIn(0f, 1f)) else it },
    updatedAt = Instant.ofEpochMilli(updatedAt),
)
