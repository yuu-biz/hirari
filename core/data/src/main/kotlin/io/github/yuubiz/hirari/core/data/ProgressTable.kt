package io.github.yuubiz.hirari.core.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import java.time.Instant

internal enum class PositionKind { Image, Text }

/** [positionAt] and [positionTotal] are 0 when [positionKind] is `null`. */
@Entity(tableName = "book_progress")
internal data class BookProgressRow(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    val state: ReadState,
    @ColumnInfo(name = "position_kind") val positionKind: PositionKind?,
    @ColumnInfo(name = "position_at") val positionAt: Long,
    @ColumnInfo(name = "position_total") val positionTotal: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
internal interface BookProgressDao {
    @Query("SELECT * FROM book_progress WHERE book_id = :bookId")
    suspend fun get(bookId: String): BookProgressRow?

    @Upsert
    suspend fun upsert(rows: List<BookProgressRow>)
}

internal fun BookProgress.toRow(): BookProgressRow {
    val (kind, at, total) = when (val p = position) {
        null -> Triple(null, 0L, 0L)
        is ReadingPosition.Image -> Triple(PositionKind.Image, p.viewStart.toLong(), p.pageCount.toLong())
        is ReadingPosition.Text -> Triple(PositionKind.Text, p.charOffset, p.totalChars)
    }
    return BookProgressRow(book.value, state, kind, at, total, updatedAt.toEpochMilli())
}

internal fun BookProgressRow.toModel(): BookProgress = BookProgress(
    book = BookId(bookId),
    state = state,
    position = when (positionKind) {
        null -> null
        PositionKind.Image -> ReadingPosition.Image(positionAt.toInt(), positionTotal.toInt())
        PositionKind.Text -> ReadingPosition.Text(positionAt, positionTotal)
    },
    updatedAt = Instant.ofEpochMilli(updatedAt),
)
