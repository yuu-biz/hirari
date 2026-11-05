package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import java.time.Instant

/**
 * The progress the reader saves for one book (docs/spec.md section 2.7). [opened] is what the store held when the
 * book opened, after every earlier write reached it. [next] gives only what the store does not hold yet.
 * An incomplete archive index cannot establish the book's end (section 3.3), so it never changes a book to read.
 */
class ProgressLog(
    private val book: BookId,
    private val pageCount: Int,
    private val opened: BookProgress?,
    private val indexComplete: Boolean = true,
) {
    private var read = opened?.state == ReadState.Read
    private var stored: BookProgress? = opened?.takeIf { it.state != ReadState.Unread }
    private var restoring = false

    /** The progress to save for [state], or `null` when the store already holds it. */
    fun next(state: ReaderState, now: Instant): BookProgress? {
        if (restoring) return null
        if (indexComplete && state.showsLastView) read = true
        val position = state.scroll?.anchor?.let { ReadingPosition.Image(it.page, pageCount, it.fraction) }
            ?: ReadingPosition.Image(state.current.pages.first(), pageCount)
        val readState = if (read) ReadState.Read else ReadState.Reading
        val last = stored
        if (last != null && last.position == position && last.state == readState) return null
        return BookProgress(book, readState, position, now).also { stored = it }
    }

    /**
     * The progress the book had before it opened, for 位置を戻して閉じる (docs/spec.md section 4.8). The reader is
     * closing, so [next] gives nothing after it.
     */
    fun restored(now: Instant): BookProgress {
        restoring = true
        return opened ?: BookProgress(book, ReadState.Unread, null, now)
    }
}
