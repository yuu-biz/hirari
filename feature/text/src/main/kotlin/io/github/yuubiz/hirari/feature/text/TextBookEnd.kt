package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.BookEndOutcome
import io.github.yuubiz.hirari.core.model.bookEndOutcome

/**
 * The texts beside the open one in its folder, which the library knows (docs/spec.md sections 4.3 and 4.9). The
 * app provides it, since the reader does not depend on the library.
 */
fun interface TextSiblings {
    /** The text after [book] in its folder toward [BookEdge.End], or before it toward [BookEdge.Start]. */
    suspend fun beside(book: TextBookRef, edge: BookEdge): TextSibling

    companion object {
        /** For a reader with no library: no text has a folder. */
        val None = TextSiblings { _, _ -> TextSibling.NoFolder }
    }
}

sealed interface TextSibling {
    data class Found(val book: TextBookRef.InLocation) : TextSibling

    /** The text is the last (or first) of its folder. */
    data object Last : TextSibling

    /** The text was picked on its own, so it has no folder to look in, or the folder could not be read. */
    data object NoFolder : TextSibling
}

/** Where a neighboring text opens: 続きから, 先頭から, or 末尾から. */
enum class TextStart { Saved, First, Last }

/** The text to open and where in it. */
data class TextOpening(val book: TextBookRef.InLocation, val start: TextStart)

fun TextSibling.Found.opening(start: TextStart) = TextOpening(book, start)

/** What the screen does for a turn past an end of the text, once the neighboring text is known where it is needed. */
sealed interface TextBookEnd {
    /** The sheet of 確認する. */
    data class Ask(val edge: BookEdge) : TextBookEnd

    data class Open(val opening: TextOpening) : TextBookEnd

    /** There is no text to open: say so. */
    data class Missing(val edge: BookEdge, val sibling: TextSibling) : TextBookEnd

    data object Close : TextBookEnd
}

/**
 * The effect of running into [edge] under [action] (docs/spec.md sections 4.8 and 4.9). 次の本へ opens the next
 * text at its start and the previous at its last view. A bounce and doing nothing have no effect beyond the motion.
 */
suspend fun textBookEnd(edge: BookEdge, action: BookEndAction, sibling: suspend (BookEdge) -> TextSibling): TextBookEnd? =
    when (bookEndOutcome(edge, action, autoPlaying = false)) {
        is BookEndOutcome.Ask -> TextBookEnd.Ask(edge)
        is BookEndOutcome.OpenNeighbor -> when (val found = sibling(edge)) {
            is TextSibling.Found -> TextBookEnd.Open(found.opening(if (edge == BookEdge.End) TextStart.First else TextStart.Last))
            TextSibling.Last, TextSibling.NoFolder -> TextBookEnd.Missing(edge, found)
        }
        BookEndOutcome.CloseReader -> TextBookEnd.Close
        BookEndOutcome.Bounce, BookEndOutcome.Ignore, BookEndOutcome.StopAutoPlay -> null
    }
