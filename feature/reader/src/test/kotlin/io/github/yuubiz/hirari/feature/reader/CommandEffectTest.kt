package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.StopOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

private fun ReaderConfig.configured(command: ReaderCommand): ReaderConfig = assertIs<CommandEffect.Configure>(effectOf(command)).config

class CommandEffectTest {
    private val config = ReaderConfig()

    @Test
    fun thePanelHoldsTheChosenCommandBarItemsInTheirOrder() {
        val chosen = listOf(ReaderCommand.Gutter, ReaderCommand.Layout, ReaderCommand.StopOrder)
        val state = readerOf(List(2) { TALL }, spreadConfig().copy(commandBarItems = chosen), PORTRAIT)
        val panel = state.commandPanel()
        assertEquals(chosen, panel.commands)
        val cell = panel.bounds.width / panel.columns
        val secondRow = panel.bounds.top + panel.bounds.height / panel.rows * 1.5f
        assertEquals(PanelItem.Command(ReaderCommand.Layout), panel.itemAt(Point(cell * 1.5f, secondRow)))
    }

    @Test
    fun theBookOverridesWinOverTheGlobalSettings() {
        val settings = GlobalSettings(readingDirection = ReadingDirection.RightToLeft, scaleMode = ScaleMode.FitScreen, commandBarItems = listOf(ReaderCommand.Gutter))
        val config = ReaderConfig.of(settings, BookOverrides(direction = ReadingDirection.LeftToRight, scaleMode = ScaleMode.FitWidth))
        assertEquals(ReadingDirection.LeftToRight, config.direction)
        assertEquals(ScaleMode.FitWidth, config.scaleMode)
        assertEquals(settings.layoutMode, config.layout.mode)
        assertEquals(listOf(ReaderCommand.Gutter), config.commandBarItems)
    }

    @Test
    fun theDefaultItemsStayTheFourOfTheSpec() {
        assertEquals(listOf(ReaderCommand.Orientation, ReaderCommand.Trim, ReaderCommand.Layout, ReaderCommand.ScaleMode), ReaderCommand.Defaults)
    }

    @Test
    fun commandsOfLaterMilestonesSayTheyAreNotYetThere() {
        for (command in listOf(ReaderCommand.Trim, ReaderCommand.Breath, ReaderCommand.SharePage)) {
            assertEquals(CommandEffect.NotYet, config.effectOf(command), "$command")
        }
    }

    @Test
    fun layoutAndScaleModeStepThroughTheirChoices() {
        val layouts = generateSequence(config) { it.configured(ReaderCommand.Layout) }.take(LayoutMode.entries.size + 1).map { it.layout.mode }.toList()
        assertEquals(LayoutMode.entries.toSet(), layouts.toSet())
        assertEquals(layouts.first(), layouts.last())
        val modes = generateSequence(config) { it.configured(ReaderCommand.ScaleMode) }.take(ScaleMode.entries.size + 1).map { it.scaleMode }.toList()
        assertEquals(ScaleMode.entries.toSet(), modes.toSet())
        assertEquals(modes.first(), modes.last())
    }

    @Test
    fun directionMirrorAndStopOrderFlip() {
        assertEquals(ReadingDirection.LeftToRight, config.configured(ReaderCommand.Direction).direction)
        assertEquals(true, config.configured(ReaderCommand.MirrorControls).mirrored)
        assertEquals(StopOrder.ColumnsFirst, config.configured(ReaderCommand.StopOrder).stopOrder)
    }

    @Test
    fun theGutterGoesFromGapToShadowToNoneAndBack() {
        val shadow = config.configured(ReaderCommand.Gutter)
        val none = shadow.configured(ReaderCommand.Gutter)
        assertEquals(listOf(Gutter.Gap, Gutter.Shadow, Gutter.None, Gutter.Gap), listOf(config.gutter, shadow.gutter, none.gutter, none.configured(ReaderCommand.Gutter).gutter))
        assertEquals(listOf(2f, 0f, 0f), listOf(config.gutterPx, shadow.gutterPx, none.gutterPx))
    }

    @Test
    fun noGapPutsThePairEdgeToEdge() {
        val gap = readerOf(List(2) { TALL })
        val none = gap.reduce(ReaderEvent.ConfigChanged(gap.config.copy(gutter = Gutter.None)))
        assertEquals(PageView.Two(0, 1), none.current)
        val (left, right) = none.geometry()!!.pages.sortedBy { it.left }
        assertEquals(left.right, right.left, 0.01f)
        val (gapLeft, gapRight) = gap.geometry()!!.pages.sortedBy { it.left }
        assertNotEquals(gapLeft.right, gapRight.left)
    }
}
