package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.TapLayout
import io.github.yuubiz.hirari.core.model.VolumeKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class TextInputTest {
    private val defaults = GlobalSettings()

    @Test
    fun `the default right band goes back and every other tap goes forward, and mirroring moves the band`() {
        assertEquals(-1, tapTurn(950f, 300f, 1000f, 600f, defaults))
        assertEquals(1, tapTurn(500f, 300f, 1000f, 600f, defaults))
        assertEquals(1, tapTurn(10f, 300f, 1000f, 600f, defaults))
        val mirrored = defaults.copy(mirrorControls = true)
        assertEquals(-1, tapTurn(50f, 300f, 1000f, 600f, mirrored))
        assertEquals(1, tapTurn(950f, 300f, 1000f, 600f, mirrored))
    }

    @Test
    fun `two bands leave a middle that does nothing and cap each band at 45 percent`() {
        val both = defaults.copy(tapLayout = TapLayout.BothSideBands, tapBandPercent = 90)
        assertEquals(null, tapTurn(500f, 300f, 1000f, 600f, both))
        assertEquals(1, tapTurn(440f, 300f, 1000f, 600f, both))
        assertEquals(-1, tapTurn(560f, 300f, 1000f, 600f, both))
        val topBottom = defaults.copy(tapLayout = TapLayout.TopAndBottomBands, tapBandPercent = 20)
        assertEquals(-1, tapTurn(500f, 50f, 1000f, 600f, topBottom))
        assertEquals(null, tapTurn(500f, 300f, 1000f, 600f, topBottom))
        assertEquals(1, tapTurn(500f, 590f, 1000f, 600f, topBottom))
    }

    @Test
    fun `a swipe to the right goes forward in a text, unless mirrored or off`() {
        assertEquals(1, swipeTurn(80f, 10f, 8f, defaults))
        assertEquals(-1, swipeTurn(-80f, 10f, 8f, defaults))
        assertEquals(-1, swipeTurn(80f, 10f, 8f, defaults.copy(mirrorControls = true)))
        assertEquals(null, swipeTurn(80f, 10f, 8f, defaults.copy(swipeToTurn = false)))
        assertEquals(null, swipeTurn(20f, 60f, 8f, defaults))
        assertEquals(null, swipeTurn(5f, 0f, 8f, defaults))
    }

    @Test
    fun `keys turn as in a right-bound book and the volume keys follow their setting`() {
        assertEquals(KeyOutcome.Act(TextAction.Turn(1)), onKey(KeyCodes.DPAD_LEFT, true, false, defaults))
        assertEquals(KeyOutcome.Act(TextAction.Turn(-1)), onKey(KeyCodes.DPAD_RIGHT, true, false, defaults))
        assertEquals(KeyOutcome.Act(TextAction.Turn(-1)), onKey(KeyCodes.DPAD_LEFT, true, false, defaults.copy(mirrorControls = true)))
        assertEquals(KeyOutcome.Act(TextAction.Turn(-1)), onKey(KeyCodes.SPACE, true, true, defaults))
        assertEquals(KeyOutcome.Act(TextAction.Turn(1)), onKey(KeyCodes.VOLUME_DOWN, true, false, defaults))
        assertEquals(KeyOutcome.Consumed, onKey(KeyCodes.VOLUME_DOWN, false, false, defaults))
        assertEquals(KeyOutcome.Act(TextAction.Turn(1)), onKey(KeyCodes.VOLUME_UP, true, false, defaults.copy(volumeKeys = VolumeKeys.UpAdvances)))
        assertEquals(KeyOutcome.NotHandled, onKey(KeyCodes.VOLUME_DOWN, true, false, defaults.copy(volumeKeys = VolumeKeys.Off)))
        assertEquals(KeyOutcome.Act(TextAction.Last), onKey(KeyCodes.MOVE_END, true, false, defaults))
        assertEquals(KeyOutcome.Act(TextAction.Dismiss), onKey(KeyCodes.BACK, true, false, defaults))
        assertEquals(KeyOutcome.NotHandled, onKey(19, true, false, defaults))
    }

    @Test
    fun `the page bar is slow for two pages and then moves one page every 5 dp`() {
        assertEquals(listOf(0, 1, 1, 2, 3, 4, 5), listOf(9f, 10f, 29f, 30f, 45f, 50f, 55f).map(::pageBarPages))
        assertEquals(-2, pageBarPages(-30f))
    }

    @Test
    fun `a touch at the very top or bottom goes to the system while the bars are hidden`() {
        assertEquals(null, regionAt(3f, 800f, defaults))
        assertEquals(TextRegion.CommandBar, regionAt(30f, 800f, defaults))
        assertEquals(TextRegion.TurnArea, regionAt(400f, 800f, defaults))
        assertEquals(TextRegion.PageBar, regionAt(770f, 800f, defaults))
        assertEquals(TextRegion.CommandBar, regionAt(3f, 800f, defaults.copy(hideSystemBars = false)))
    }
}
