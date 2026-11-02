package io.github.yuubiz.hirari.feature.reader

/** The `atBookEnd` setting. */
enum class BookEndAction { Ask, NextBook, Close, Stop }

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
