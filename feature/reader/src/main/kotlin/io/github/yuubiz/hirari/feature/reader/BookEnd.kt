package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.StartAt
import kotlin.math.PI
import kotlin.math.sin

enum class BookEdge { Start, End }

sealed interface BookEndOutcome {
    /** A short bounce at the edge. */
    data object Bounce : BookEndOutcome
    data object CloseReader : BookEndOutcome
    data object Ignore : BookEndOutcome
    data object StopAutoPlay : BookEndOutcome

    /** Outcomes that need the neighboring book in the folder, which the library finds. */
    sealed interface NeedsNeighbor : BookEndOutcome {
        val edge: BookEdge
    }

    /** The sheet with the neighboring book's choices and closing. */
    data class Ask(override val edge: BookEdge) : NeedsNeighbor

    /** The next book from its start, or the previous book at its last view. */
    data class OpenNeighbor(override val edge: BookEdge) : NeedsNeighbor
}

/** What a turn past [edge] does (docs/spec.md section 4.8, 本の端での動作). */
fun bookEndOutcome(edge: BookEdge, action: BookEndAction, autoPlaying: Boolean): BookEndOutcome = when {
    autoPlaying && edge == BookEdge.End -> BookEndOutcome.StopAutoPlay
    action == BookEndAction.Ask -> BookEndOutcome.Ask(edge)
    action == BookEndAction.NextBook -> BookEndOutcome.OpenNeighbor(edge)
    action == BookEndAction.Close -> if (edge == BookEdge.End) BookEndOutcome.CloseReader else BookEndOutcome.Ignore
    else -> BookEndOutcome.Bounce
}

/** The edge a [turn] that cannot move has run into. */
fun edgeOf(turn: Turn): BookEdge = if (turn == Turn.Forward) BookEdge.End else BookEdge.Start

/** A turn toward [edge] at [atMs] that found no view there. */
data class EdgeHit(val edge: BookEdge, val atMs: Long) {
    fun isDoneAt(nowMs: Long): Boolean = nowMs - atMs >= BOUNCE_MS

    /**
     * How far the bounce of 止まる moves the view at [nowMs], in dp: out toward where the next view would come
     * from and back, as a turn that gives up.
     */
    fun bounceDpAt(nowMs: Long, direction: ReadingDirection): Float {
        val progress = ((nowMs - atMs).toFloat() / BOUNCE_MS).coerceIn(0f, 1f)
        val turn = if (edge == BookEdge.End) Turn.Forward else Turn.Back
        return turnMovement(turn, direction) * BOUNCE_DP * sin(PI.toFloat() * progress)
    }

    companion object {
        const val BOUNCE_MS = 240L
        const val BOUNCE_DP = 24f
    }
}

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
