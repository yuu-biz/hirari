package io.github.yuubiz.hirari.core.model

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BookEndEffectTest {
    private val asked = mutableListOf<BookEdge>()

    private fun neighbors(edge: BookEdge): Sibling<String> = when (edge) {
        BookEdge.End -> Sibling.Found("next")
        BookEdge.Start -> Sibling.Found("previous")
    }.also { asked += edge }

    private fun effect(edge: BookEdge, action: BookEndAction, sibling: (BookEdge) -> Sibling<String> = ::neighbors) =
        runBlocking { bookEndEffect(edge, action, sibling) }

    @Test
    fun askingShowsTheSheetAtEitherEndWithoutLookingTheNeighborUp() {
        assertEquals(BookEndEffect.Ask(BookEdge.End), effect(BookEdge.End, BookEndAction.Ask))
        assertEquals(BookEndEffect.Ask(BookEdge.Start), effect(BookEdge.Start, BookEndAction.Ask))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun nextBookOpensTheNextFromItsStartAndThePreviousAtItsEnd() {
        assertEquals(BookEndEffect.Open(NeighborOpening("next", NeighborStart.First)), effect(BookEdge.End, BookEndAction.NextBook))
        assertEquals(BookEndEffect.Open(NeighborOpening("previous", NeighborStart.Last)), effect(BookEdge.Start, BookEndAction.NextBook))
        assertEquals(listOf(BookEdge.End, BookEdge.Start), asked)
    }

    @Test
    fun noNeighborSaysWhy() {
        assertEquals(BookEndEffect.Missing(BookEdge.End, Sibling.Last), effect(BookEdge.End, BookEndAction.NextBook) { Sibling.Last })
        assertEquals(BookEndEffect.Missing(BookEdge.Start, Sibling.NoFolder), effect(BookEdge.Start, BookEndAction.NextBook) { Sibling.NoFolder })
    }

    @Test
    fun closeClosesAtTheEndOnlyAndStopLeavesTheBounceToTheReader() {
        assertEquals(BookEndEffect.Close, effect(BookEdge.End, BookEndAction.Close))
        assertNull(effect(BookEdge.Start, BookEndAction.Close))
        assertNull(effect(BookEdge.End, BookEndAction.Stop))
        assertNull(effect(BookEdge.Start, BookEndAction.Stop))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun aFoundSiblingOpensWhereTheChoiceSays() {
        val found = Sibling.Found("next")
        for (start in NeighborStart.entries) {
            assertEquals(NeighborOpening("next", start), found.opening(start))
        }
    }

    @Test
    fun aReaderWithoutALibraryFindsNoFolder() {
        runBlocking {
            assertEquals(Sibling.NoFolder, Siblings.None.beside("book", BookEdge.End))
            assertIs<Sibling.NoFolder>(Siblings.None.beside(1, BookEdge.Start))
        }
    }
}
