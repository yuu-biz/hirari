package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BarDisplay
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderRegions
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.TapLayout
import io.github.yuubiz.hirari.core.model.TapZones
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.model.VolumeKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideTest {
    private val area = Size(400f, 800f)

    private fun rectsOf(zones: TapZones) = tapZoneRects(zones, area)

    private fun assertRect(expected: Rect, actual: Rect) {
        for ((a, b) in listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)) {
            assertEquals(a, b, 0.02f)
        }
    }

    @Test
    fun `the default layout is a right tenth that goes back and the rest forward`() {
        val zones = rectsOf(TapZones())
        assertEquals(listOf(Turn.Forward, Turn.Back), zones.map { it.turn })
        assertRect(Rect(0f, 0f, 360f, 800f), zones[0].rect)
        assertRect(Rect(360f, 0f, 400f, 800f), zones[1].rect)
    }

    @Test
    fun `mirroring moves the back band to the left`() {
        val zones = rectsOf(TapZones(mirrored = true))
        assertEquals(listOf(Turn.Back, Turn.Forward), zones.map { it.turn })
        assertRect(Rect(0f, 0f, 40f, 800f), zones[0].rect)
    }

    @Test
    fun `two side bands leave the middle doing nothing`() {
        val zones = rectsOf(TapZones(TapLayout.BothSideBands, bandPercent = 20))
        assertEquals(listOf(Turn.Forward, null, Turn.Back), zones.map { it.turn })
        assertRect(Rect(0f, 0f, 80f, 800f), zones[0].rect)
        assertRect(Rect(80f, 0f, 320f, 800f), zones[1].rect)
        assertRect(Rect(320f, 0f, 400f, 800f), zones[2].rect)
    }

    @Test
    fun `bands wider than 45 percent are drawn as 45 percent`() {
        val zones = rectsOf(TapZones(TapLayout.BothSideBands, bandPercent = 80))
        assertRect(Rect(180f, 0f, 220f, 800f), zones[1].rect)
    }

    @Test
    fun `the top and bottom layouts cut the turn area across`() {
        val one = rectsOf(TapZones(TapLayout.TopAndBottom, bandPercent = 25))
        assertEquals(listOf(Turn.Back, Turn.Forward), one.map { it.turn })
        assertRect(Rect(0f, 0f, 400f, 200f), one[0].rect)
        assertRect(Rect(0f, 200f, 400f, 800f), one[1].rect)

        val two = rectsOf(TapZones(TapLayout.TopAndBottomBands, bandPercent = 25))
        assertEquals(listOf(Turn.Back, null, Turn.Forward), two.map { it.turn })
        assertRect(Rect(0f, 600f, 400f, 800f), two[2].rect)
    }

    @Test
    fun `every layout is covered by zones that agree with the tap they stand for`() {
        for (layout in TapLayout.entries) for (percent in listOf(10, 25, 45, 60, 90)) for (mirrored in listOf(false, true)) {
            val zones = TapZones(layout, percent, mirrored)
            val rects = tapZoneRects(zones, area)
            assertEquals(0f, minOf(rects.minOf { it.rect.left }, rects.minOf { it.rect.top }))
            assertEquals(area.width * area.height, rects.sumOf { (it.rect.width * it.rect.height).toDouble() }.toFloat(), 1f)
            var x = 0.5f
            while (x < area.width) {
                var y = 0.5f
                while (y < area.height) {
                    val inside = rects.filter { x >= it.rect.left && x < it.rect.right && y >= it.rect.top && y < it.rect.bottom }
                    assertEquals(1, inside.size, "$layout $percent $mirrored at $x,$y")
                    assertEquals(zones.turnAt(Point(x, y), area), inside.single().turn, "$layout $percent $mirrored at $x,$y")
                    y += 13.7f
                }
                x += 9.3f
            }
        }
    }

    private val regions = ReaderRegions(Size(400f, 900f), barHeight = 50f)

    @Test
    fun `the guide puts the zones in the turn area between the two bars`() {
        val content = guideContent(regions, ReaderConfig(barDisplay = BarDisplay.Hidden))
        assertEquals(Rect(0f, 0f, 400f, 50f), content.layout.commandBar)
        assertEquals(Rect(0f, 850f, 400f, 900f), content.layout.pageBar)
        assertEquals(50f, content.layout.zones.minOf { it.rect.top })
        assertEquals(850f, content.layout.zones.maxOf { it.rect.bottom })
        assertEquals(Turn.Back, content.layout.zones.last().turn)
        assertRect(Rect(360f, 50f, 400f, 850f), content.layout.zones.last().rect)
    }

    @Test
    fun `swiping goes forward toward the side the reader pulls forward`() {
        assertEquals(Side.Right, guideContent(regions, ReaderConfig(barDisplay = BarDisplay.Hidden)).swipeForward)
        val leftToRight = ReaderConfig(barDisplay = BarDisplay.Hidden, layout = ReaderConfig(barDisplay = BarDisplay.Hidden).layout.copy(direction = ReadingDirection.LeftToRight))
        assertEquals(Side.Left, guideContent(regions, leftToRight).swipeForward)
        val mirrored = ReaderConfig(barDisplay = BarDisplay.Hidden, tapZones = TapZones(mirrored = true))
        assertEquals(Side.Left, guideContent(regions, mirrored).swipeForward)
    }

    @Test
    fun `a drag only scrolls when swiping to turn is off`() {
        assertNull(guideContent(regions, ReaderConfig(barDisplay = BarDisplay.Hidden, swipeToTurn = false)).swipeForward)
    }

    @Test
    fun `the left key follows the binding direction and the mirroring`() {
        assertEquals(Turn.Forward, guideContent(regions, ReaderConfig(barDisplay = BarDisplay.Hidden)).leftKeyTurn)
        val leftToRight = ReaderConfig(barDisplay = BarDisplay.Hidden, layout = ReaderConfig(barDisplay = BarDisplay.Hidden).layout.copy(direction = ReadingDirection.LeftToRight))
        assertEquals(Turn.Back, guideContent(regions, leftToRight).leftKeyTurn)
        assertEquals(Turn.Back, guideContent(regions, ReaderConfig(barDisplay = BarDisplay.Hidden, tapZones = TapZones(mirrored = true))).leftKeyTurn)
    }

    @Test
    fun `the volume keys are listed as the setting has them`() {
        assertEquals(VolumeKeys.Off, guideContent(regions, ReaderConfig(barDisplay = BarDisplay.Hidden, volumeKeys = VolumeKeys.Off)).volumeKeys)
        assertTrue(guideContent(regions, ReaderConfig(barDisplay = BarDisplay.Hidden)).volumeKeys != VolumeKeys.Off)
    }

    @Test
    fun `the guide closes first and takes the back key before any panel`() {
        assertEquals(DismissTarget.Guide, dismissTarget(autoPlaying = false, guideShown = true, panelOpen = true, opening = false))
    }
}
