package io.github.yuubiz.hirari.core.model

import java.time.Instant

sealed interface ReadingPosition {
    /**
     * Opening the book makes [viewStart] the anchor. In 縦に連続, [viewStart] is the page at the top of the screen and
     * [offset] how far down that page the top of the screen is, as a fraction of its height; 0 in the other layouts.
     */
    data class Image(val viewStart: Int, val pageCount: Int, val offset: Float = 0f) : ReadingPosition
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
