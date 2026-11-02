package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition

/** The read-state mark of a book in a list (docs/spec.md section 4.3, 読書状態). */
sealed interface ReadMark {
    data object Unread : ReadMark

    data class Reading(val percent: Int) : ReadMark

    data object Read : ReadMark
}

/** How far into the book the stored position is, from 0 to 100: the share of pages up to the shown view. */
fun BookProgress.percent(): Int = when (val at = position) {
    is ReadingPosition.Image -> if (at.pageCount > 0) ((at.viewStart + 1) * 100L / at.pageCount).toInt() else 0
    is ReadingPosition.Text -> if (at.totalChars > 0) (at.charOffset * 100 / at.totalChars).toInt() else 0
    null -> 0
}.coerceIn(0, 100)

fun BookProgress?.mark(): ReadMark = when (this?.state) {
    null, ReadState.Unread -> ReadMark.Unread
    ReadState.Reading -> ReadMark.Reading(percent())
    ReadState.Read -> ReadMark.Read
}
