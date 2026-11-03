package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.BookEndEffect
import io.github.yuubiz.hirari.core.model.NeighborOpening
import io.github.yuubiz.hirari.core.model.NeighborStart
import io.github.yuubiz.hirari.core.model.Sibling
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookEndsTest {
    private var lookups = 0
    private val answer = CompletableDeferred<Sibling<String>>()

    private fun TestScope.ends() = BookEnds<String>(backgroundScope) {
        lookups++
        answer.await()
    }

    @Test
    fun `the text beside the end opens once the library answers`() = runTest {
        val ends = ends()
        ends.reached(BookEdge.End, BookEndAction.NextBook)
        runCurrent()
        assertNull(ends.effect)
        answer.complete(Sibling.Found("next"))
        runCurrent()
        assertEquals(BookEndEffect.Open(NeighborOpening("next", NeighborStart.First)), ends.effect)
    }

    @Test
    fun `an answer that comes after a later page action does not take the reader away`() = runTest {
        val ends = ends()
        ends.reached(BookEdge.End, BookEndAction.NextBook)
        runCurrent()
        ends.pageActed()
        answer.complete(Sibling.Found("next"))
        runCurrent()
        assertNull(ends.effect)
    }

    @Test
    fun `running into the same end again after a page action uses the lookup that is already going`() = runTest {
        val ends = ends()
        ends.reached(BookEdge.End, BookEndAction.NextBook)
        runCurrent()
        ends.pageActed()
        ends.reached(BookEdge.End, BookEndAction.NextBook)
        runCurrent()
        answer.complete(Sibling.Found("next"))
        runCurrent()
        assertEquals(1, lookups)
        assertEquals(BookEndEffect.Open(NeighborOpening("next", NeighborStart.First)), ends.effect)
    }

    @Test
    fun `closing the sheet while the library is asked does not ask for the same end again`() = runTest {
        val ends = ends()
        val sheet = launch { ends.sibling(BookEdge.End) }
        runCurrent()
        sheet.cancel()
        runCurrent()
        val again = async { ends.sibling(BookEdge.End) }
        runCurrent()
        answer.complete(Sibling.Last)
        runCurrent()
        assertEquals(Sibling.Last, again.await())
        assertEquals(1, lookups)
        assertEquals(Sibling.Last, ends.sibling(BookEdge.End))
        assertEquals(1, lookups)
    }
}
