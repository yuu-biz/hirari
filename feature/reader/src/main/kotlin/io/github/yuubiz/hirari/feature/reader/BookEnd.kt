package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.BookEndOutcome
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.bookEndOutcome

/**
 * The books beside the open one in its folder, which the library knows (docs/spec.md sections 4.3 and 4.8). The app
 * provides it, since the reader does not depend on the library.
 */
fun interface SiblingBooks {
    /** The book after [book] in its folder toward [BookEdge.End], or before it toward [BookEdge.Start]. */
    suspend fun beside(book: BookAddress, edge: BookEdge): Sibling

    companion object {
        /** For a reader with no library: no book has a folder. */
        val None = SiblingBooks { _, _ -> Sibling.NoFolder }
    }
}

sealed interface Sibling {
    data class Found(val book: BookAddress) : Sibling

    /** The book is the last (or first) of its folder. */
    data object Last : Sibling

    /** The book was picked on its own, so it has no folder to look in, or the folder could not be read. */
    data object NoFolder : Sibling
}

/** Where a neighboring book opens: 続きから, 先頭から, or 末尾から. */
enum class NeighborStart { Saved, First, Last }

fun Sibling.Found.request(start: NeighborStart): OpenBookRequest = OpenBookRequest(
    book,
    when (start) {
        NeighborStart.Saved -> StartAt.Saved
        NeighborStart.First -> StartAt.Page(0)
        NeighborStart.Last -> StartAt.Page(Int.MAX_VALUE)
    },
)

/** What the screen does for a turn past an edge, once the neighboring book is known where it is needed. */
sealed interface BookEndEffect {
    /** The sheet of 確認する. */
    data class Ask(val edge: BookEdge) : BookEndEffect

    data class Open(val request: OpenBookRequest) : BookEndEffect

    /** There is no book to open: say so. */
    data class Missing(val edge: BookEdge, val sibling: Sibling) : BookEndEffect

    data object Close : BookEndEffect
}

/**
 * The effect of running into [edge] under [action]. 次の本へ opens the next book at its start and the previous
 * at its last view. A bounce and doing nothing have no effect beyond the state's own.
 */
suspend fun bookEndEffect(edge: BookEdge, action: BookEndAction, sibling: suspend (BookEdge) -> Sibling): BookEndEffect? =
    when (bookEndOutcome(edge, action, autoPlaying = false)) {
        is BookEndOutcome.Ask -> BookEndEffect.Ask(edge)
        is BookEndOutcome.OpenNeighbor -> when (val found = sibling(edge)) {
            is Sibling.Found -> BookEndEffect.Open(found.request(if (edge == BookEdge.End) NeighborStart.First else NeighborStart.Last))
            Sibling.Last, Sibling.NoFolder -> BookEndEffect.Missing(edge, found)
        }
        BookEndOutcome.CloseReader -> BookEndEffect.Close
        BookEndOutcome.Bounce, BookEndOutcome.Ignore, BookEndOutcome.StopAutoPlay -> null
    }
