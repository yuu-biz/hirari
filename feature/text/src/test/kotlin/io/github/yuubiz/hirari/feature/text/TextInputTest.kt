package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.KeyCodes
import io.github.yuubiz.hirari.core.model.KeyResult
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.Turn
import kotlin.test.Test
import kotlin.test.assertEquals

class TextInputTest {
    private val defaults = GlobalSettings()

    private fun views(code: Int, settings: GlobalSettings = defaults) =
        (settings.textKeys().onKey(code, pressed = true) as? KeyResult.Act)?.action?.textTurn()?.views

    @Test
    fun `the left key and the volume keys turn a text bound on the right`() {
        assertEquals(1, views(KeyCodes.DPAD_LEFT))
        assertEquals(-1, views(KeyCodes.DPAD_RIGHT))
        assertEquals(-1, views(KeyCodes.DPAD_LEFT, defaults.copy(mirrorControls = true)))
        assertEquals(1, views(KeyCodes.VOLUME_DOWN))
        assertEquals(-1, views(KeyCodes.VOLUME_UP))
    }

    @Test
    fun `shifting the pairing does nothing in a text`() {
        assertEquals(null, views(KeyCodes.DPAD_UP))
        assertEquals(null, ReaderAction.Shift(forward = true).textTurn())
        assertEquals(Turn.Forward, ReaderAction.StepStops(Turn.Forward).textTurn())
    }

    private val partial = PageRun(intArrayOf(100, 200, 300), end = 400, fromStart = false, complete = false, sourceLength = 1000)

    @Test
    fun `an end page picked at the edge of the bar goes to the end of the text before it is all laid out`() {
        assertEquals(PageBarJump.Last, pageBarJump(2, atEdge = true, partial))
        assertEquals(PageBarJump.First, pageBarJump(0, atEdge = true, partial))
        assertEquals(PageBarJump.Page(300), pageBarJump(2, atEdge = false, partial))
        val whole = PageRun(intArrayOf(0, 100, 200), end = 300, fromStart = true, complete = true, sourceLength = 300)
        assertEquals(PageBarJump.Page(200), pageBarJump(2, atEdge = true, whole))
        assertEquals(PageBarJump.Page(0), pageBarJump(0, atEdge = true, whole))
    }

    @Test
    fun `a page of the run pressed on is found again in the run laid out since`() {
        val grown = PageRun(intArrayOf(0, 50, 100, 200, 300), end = 400, fromStart = true, complete = false, sourceLength = 1000)
        assertEquals(4, pageInLaterRun(partial, 2, grown))
        assertEquals(2, pageInLaterRun(partial, 0, grown))
        assertEquals(4, pageInLaterRun(partial, 9, grown))
    }
}
