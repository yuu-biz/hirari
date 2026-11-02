package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.HalfPart
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.VolumeKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SheetsTest {
    private val keys = KeyBindings(ReadingDirection.RightToLeft, mirrored = false, volumeKeys = VolumeKeys.DownAdvances)

    private fun KeyBindings.under(sheet: ReaderSheet?, code: Int) = onKey(code, pressed = true, sheet = sheet)

    @Test
    fun withNoSheetTheReaderTakesItsKeys() {
        assertEquals(KeyResult.Act(ReaderAction.TurnView(Turn.Forward)), keys.under(null, KeyCodes.DPAD_LEFT))
        assertEquals(KeyResult.Act(ReaderAction.Dismiss), keys.under(null, KeyCodes.DEL))
    }

    @Test
    fun underTheMenuOnlyBackDelAndMenuReachTheReader() {
        assertEquals(KeyResult.Act(ReaderAction.Dismiss), keys.under(ReaderSheet.Menu, KeyCodes.BACK))
        assertEquals(KeyResult.Act(ReaderAction.Dismiss), keys.under(ReaderSheet.Menu, KeyCodes.DEL))
        assertEquals(KeyResult.Act(ReaderAction.OpenMenu), keys.under(ReaderSheet.Menu, KeyCodes.MENU))
        assertEquals(KeyResult.NotHandled, keys.under(ReaderSheet.Menu, KeyCodes.DPAD_LEFT))
        assertEquals(KeyResult.NotHandled, keys.under(ReaderSheet.Menu, KeyCodes.ENTER))
    }

    @Test
    fun underThePagePanelDelGoesToThePageNumberField() {
        val panel = ReaderSheet.Pages(openedOn = 4)
        assertEquals(KeyResult.NotHandled, keys.under(panel, KeyCodes.DEL))
        assertEquals(KeyResult.NotHandled, keys.under(panel, KeyCodes.ENTER))
        assertEquals(KeyResult.Act(ReaderAction.Dismiss), keys.under(panel, KeyCodes.BACK))
        assertEquals(KeyResult.Act(ReaderAction.OpenMenu), keys.under(panel, KeyCodes.MENU))
    }

    @Test
    fun theLabelCountsPagesFromOneAndJoinsAPair() {
        assertEquals("12 / 200", pageLabel(PageView.One(11), 200))
        assertEquals("12-13 / 200", pageLabel(PageView.Two(12, 11), 200))
        assertEquals("3 / 3", pageLabel(PageView.Half(2, HalfPart.Second), 3))
    }

    @Test
    fun theStepButtonsStopAtTheEnds() {
        assertEquals(listOf(0, 0, 4, 6, 15, 49), PagePanel.STEPS.map { PagePanel.stepped(5, it, pageCount = 50) })
    }

    @Test
    fun aTypedNumberCountsFromOneAndAnythingElseIsNone() {
        assertEquals(11, PagePanel.typed(" 12 ", pageCount = 200))
        assertEquals(199, PagePanel.typed("999999999999", pageCount = 200))
        assertEquals(0, PagePanel.typed("0", pageCount = 200))
        assertNull(PagePanel.typed("12a", pageCount = 200))
        assertNull(PagePanel.typed("", pageCount = 200))
    }

    @Test
    fun theMenuIsNarrowerThanTheShortSideAndAtMost400Dp() {
        assertEquals(342f, menuWidthDp(360f, 800f), 0.01f)
        assertEquals(342f, menuWidthDp(800f, 360f), 0.01f)
        assertEquals(400f, menuWidthDp(1280f, 800f))
    }

    @Test
    fun theMenuKeepsTheSpecsThreeGroupsInOrder() {
        assertEquals(MenuItem.entries.map { it.group }.sorted(), MenuItem.entries.map { it.group })
        assertEquals(
            setOf(MenuItem.RestoreAndClose, MenuItem.AutoPlay, MenuItem.Orientation, MenuItem.MirrorControls),
            MenuItem.entries.filter { it.available }.toSet(),
        )
    }
}
