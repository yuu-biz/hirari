package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.LocationId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextBookEndTest {
    private val next = TextBookRef.InLocation(LocationId(1), "作品/第2話.txt", "doc:2")
    private val previous = TextBookRef.InLocation(LocationId(1), "作品/第1話.txt", "doc:1")
    private val asked = mutableListOf<BookEdge>()

    private fun neighbors(edge: BookEdge) = when (edge) {
        BookEdge.End -> TextSibling.Found(next)
        BookEdge.Start -> TextSibling.Found(previous)
    }.also { asked += edge }

    private fun effect(edge: BookEdge, action: BookEndAction, sibling: (BookEdge) -> TextSibling = ::neighbors) =
        runBlocking { textBookEnd(edge, action, sibling) }

    @Test
    fun `確認する asks at either end without looking for the neighbor`() {
        assertEquals(TextBookEnd.Ask(BookEdge.End), effect(BookEdge.End, BookEndAction.Ask))
        assertEquals(TextBookEnd.Ask(BookEdge.Start), effect(BookEdge.Start, BookEndAction.Ask))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `次の本へ opens the next text from its start and the previous one at its last view`() {
        assertEquals(TextBookEnd.Open(TextOpening(next, TextStart.First)), effect(BookEdge.End, BookEndAction.NextBook))
        assertEquals(TextBookEnd.Open(TextOpening(previous, TextStart.Last)), effect(BookEdge.Start, BookEndAction.NextBook))
    }

    @Test
    fun `次の本へ says why when there is no text to open`() {
        assertEquals(TextBookEnd.Missing(BookEdge.End, TextSibling.Last), effect(BookEdge.End, BookEndAction.NextBook) { TextSibling.Last })
        assertEquals(
            TextBookEnd.Missing(BookEdge.Start, TextSibling.NoFolder),
            effect(BookEdge.Start, BookEndAction.NextBook) { TextSibling.NoFolder },
        )
    }

    @Test
    fun `閉じる closes at the end only, and 止まる leaves the bounce to the motion`() {
        assertEquals(TextBookEnd.Close, effect(BookEdge.End, BookEndAction.Close))
        assertNull(effect(BookEdge.Start, BookEndAction.Close))
        assertNull(effect(BookEdge.End, BookEndAction.Stop))
        assertNull(effect(BookEdge.Start, BookEndAction.Stop))
    }

    @Test
    fun `a text without siblings finds none`() = runBlocking {
        val book = TextBookRef.InLocation(LocationId(1), "a.txt")
        assertEquals(TextSibling.NoFolder, TextSiblings.None.beside(book, BookEdge.End))
    }
}
