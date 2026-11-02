package io.github.yuubiz.hirari.feature.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageNumberFieldTest {
    private val field = PageNumberField(head = 4)

    @Test
    fun itStartsOnThePageItShows() {
        assertEquals("5", field.text)
    }

    @Test
    fun typingKeepsEveryCharacterUntilItIsConfirmed() {
        field.edit("12a")
        assertEquals("12a", field.text)
        field.edit("-5")
        assertEquals("-5", field.text)
    }

    @Test
    fun confirmingTextThatIsNotANumberMovesNowhereAndShowsThePageAgain() {
        field.edit("12a")
        assertNull(field.confirm(pageCount = 200, head = 4))
        assertEquals("5", field.text)
    }

    @Test
    fun confirmingANumberOutOfRangeGoesToTheNearerEnd() {
        field.edit("-5")
        assertEquals(0, field.confirm(pageCount = 200, head = 4))
        field.edit("9999")
        assertEquals(199, field.confirm(pageCount = 200, head = 4))
    }

    @Test
    fun confirmingANumberInRangeGoesThere() {
        field.edit("12")
        assertEquals(11, field.confirm(pageCount = 200, head = 4))
    }

    @Test
    fun showingAPageReplacesWhatWasTyped() {
        field.edit("12")
        field.show(head = 30)
        assertEquals("31", field.text)
    }
}
