package io.github.yuubiz.hirari.core.model

/**
 * The books beside the open one in its folder, which the library knows (docs/spec.md sections 4.3, 4.8 and 4.9). The
 * app provides it, since the readers do not depend on the library. [B] is how a reader names a book and [F] the
 * kind of book it can open beside it.
 */
fun interface Siblings<in B, out F> {
    /** The book after [book] in its folder toward [BookEdge.End], or before it toward [BookEdge.Start]. */
    suspend fun beside(book: B, edge: BookEdge): Sibling<F>

    companion object {
        /** For a reader with no library: no book has a folder. */
        val None = Siblings<Any?, Nothing> { _, _ -> Sibling.NoFolder }
    }
}

sealed interface Sibling<out F> {
    data class Found<out F>(val book: F) : Sibling<F>

    /** The book is the last (or first) of its folder. */
    data object Last : Sibling<Nothing>

    /** The book was picked on its own, so it has no folder to look in, or the folder could not be read. */
    data object NoFolder : Sibling<Nothing>
}

/** Where a neighboring book opens: 続きから, 先頭から, or 末尾から. */
enum class NeighborStart { Saved, First, Last }

/** The book to open and where in it. */
data class NeighborOpening<out F>(val book: F, val start: NeighborStart)

fun <F> Sibling.Found<F>.opening(start: NeighborStart) = NeighborOpening(book, start)

/** What a reader does for a turn past an edge, once the neighboring book is known where it is needed. */
sealed interface BookEndEffect<out F> {
    /** The sheet of 確認する. */
    data class Ask(val edge: BookEdge) : BookEndEffect<Nothing>

    data class Open<out F>(val opening: NeighborOpening<F>) : BookEndEffect<F>

    /** There is no book to open: say so. */
    data class Missing<out F>(val edge: BookEdge, val sibling: Sibling<F>) : BookEndEffect<F>

    data object Close : BookEndEffect<Nothing>
}

/**
 * The effect of running into [edge] under [action]. 次の本へ opens the next book at its start and the previous at
 * its last view. A bounce and doing nothing have no effect beyond the reader's own motion.
 */
suspend fun <F> bookEndEffect(edge: BookEdge, action: BookEndAction, sibling: suspend (BookEdge) -> Sibling<F>): BookEndEffect<F>? =
    when (bookEndOutcome(edge, action, autoPlaying = false)) {
        is BookEndOutcome.Ask -> BookEndEffect.Ask(edge)
        is BookEndOutcome.OpenNeighbor -> when (val found = sibling(edge)) {
            is Sibling.Found -> BookEndEffect.Open(found.opening(if (edge == BookEdge.End) NeighborStart.First else NeighborStart.Last))
            Sibling.Last, Sibling.NoFolder -> BookEndEffect.Missing(edge, found)
        }
        BookEndOutcome.CloseReader -> BookEndEffect.Close
        BookEndOutcome.Bounce, BookEndOutcome.Ignore, BookEndOutcome.StopAutoPlay -> null
    }
