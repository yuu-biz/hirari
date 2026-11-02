package io.github.yuubiz.hirari.core.model

import java.time.Instant

sealed interface ReadingPosition {
    /** Opening the book makes [viewStart] the anchor. */
    data class Image(val viewStart: Int, val pageCount: Int) : ReadingPosition
    data class Text(val charOffset: Long, val totalChars: Long) : ReadingPosition
}

enum class ReadState { Unread, Reading, Read }

data class BookProgress(
    val book: BookId,
    val state: ReadState,
    val position: ReadingPosition?,
    val updatedAt: Instant,
)

data class Bookmark(
    val id: Long,
    val book: BookId,
    val position: ReadingPosition,
    val label: String,
    val createdAt: Instant,
)

data class HistoryEntry(val book: BookId, val openedAt: Instant)
