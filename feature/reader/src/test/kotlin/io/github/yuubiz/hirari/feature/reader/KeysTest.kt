package io.github.yuubiz.hirari.feature.reader

import android.view.KeyEvent
import io.github.yuubiz.hirari.core.model.ReadingDirection.LeftToRight
import io.github.yuubiz.hirari.core.model.ReadingDirection.RightToLeft
import io.github.yuubiz.hirari.core.model.VolumeKeys
import io.github.yuubiz.hirari.feature.reader.ReaderAction.StepStops
import io.github.yuubiz.hirari.feature.reader.ReaderAction.TurnView
import io.github.yuubiz.hirari.feature.reader.Turn.Back
import io.github.yuubiz.hirari.feature.reader.Turn.Forward
import kotlin.test.Test
import kotlin.test.assertEquals

class KeysTest {
    private val rtl = KeyBindings(RightToLeft, mirrored = false, volumeKeys = VolumeKeys.DownAdvances)

    private fun KeyBindings.press(code: Int, shift: Boolean = false) = onKey(code, pressed = true, shift = shift)

    @Test
    fun theCodesMatchTheFramework() {
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_MOVE_HOME,
                KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_NUMPAD_ENTER,
            ),
            listOf(
                KeyCodes.BACK, KeyCodes.DPAD_UP, KeyCodes.DPAD_DOWN, KeyCodes.DPAD_LEFT,
                KeyCodes.DPAD_RIGHT, KeyCodes.VOLUME_UP, KeyCodes.VOLUME_DOWN, KeyCodes.SPACE,
                KeyCodes.ENTER, KeyCodes.DEL, KeyCodes.MENU, KeyCodes.PAGE_UP,
                KeyCodes.PAGE_DOWN, KeyCodes.BUTTON_L1, KeyCodes.BUTTON_R1, KeyCodes.MOVE_HOME,
                KeyCodes.MOVE_END, KeyCodes.NUMPAD_ENTER,
            ),
        )
    }

    @Test
    fun theLeftKeyGoesForwardInARightToLeftBook() {
        assertEquals(KeyResult.Act(TurnView(Forward)), rtl.press(KeyCodes.DPAD_LEFT))
        assertEquals(KeyResult.Act(TurnView(Back)), rtl.press(KeyCodes.DPAD_RIGHT))
    }

    @Test
    fun theRightKeyGoesForwardInALeftToRightBook() {
        val ltr = rtl.copy(direction = LeftToRight)
        assertEquals(KeyResult.Act(TurnView(Forward)), ltr.press(KeyCodes.DPAD_RIGHT))
        assertEquals(KeyResult.Act(TurnView(Back)), ltr.press(KeyCodes.DPAD_LEFT))
    }

    @Test
    fun mirroringSwapsTheArrowKeys() {
        val mirrored = rtl.copy(mirrored = true)
        assertEquals(KeyResult.Act(TurnView(Back)), mirrored.press(KeyCodes.DPAD_LEFT))
        assertEquals(KeyResult.Act(TurnView(Forward)), mirrored.press(KeyCodes.DPAD_RIGHT))
    }

    @Test
    fun upAndDownShiftThePairing() {
        assertEquals(KeyResult.Act(ReaderAction.Shift(forward = false)), rtl.press(KeyCodes.DPAD_UP))
        assertEquals(KeyResult.Act(ReaderAction.Shift(forward = true)), rtl.press(KeyCodes.DPAD_DOWN))
    }

    @Test
    fun pageKeysSpaceEnterAndShouldersTurn() {
        for (code in listOf(KeyCodes.PAGE_DOWN, KeyCodes.SPACE, KeyCodes.ENTER, KeyCodes.NUMPAD_ENTER, KeyCodes.BUTTON_R1)) {
            assertEquals(KeyResult.Act(TurnView(Forward)), rtl.press(code), "key $code")
        }
        assertEquals(KeyResult.Act(TurnView(Back)), rtl.press(KeyCodes.PAGE_UP))
        assertEquals(KeyResult.Act(TurnView(Back)), rtl.press(KeyCodes.SPACE, shift = true))
        assertEquals(KeyResult.Act(TurnView(Back)), rtl.press(KeyCodes.BUTTON_L1))
    }

    @Test
    fun homeEndMenuAndBack() {
        assertEquals(KeyResult.Act(ReaderAction.First), rtl.press(KeyCodes.MOVE_HOME))
        assertEquals(KeyResult.Act(ReaderAction.Last), rtl.press(KeyCodes.MOVE_END))
        assertEquals(KeyResult.Act(ReaderAction.OpenMenu), rtl.press(KeyCodes.MENU))
        assertEquals(KeyResult.Act(ReaderAction.Dismiss), rtl.press(KeyCodes.BACK))
        assertEquals(KeyResult.Act(ReaderAction.Dismiss), rtl.press(KeyCodes.DEL))
    }

    @Test
    fun volumeKeysStepThroughStopsAndTakeTheRelease() {
        assertEquals(KeyResult.Act(StepStops(Forward)), rtl.press(KeyCodes.VOLUME_DOWN))
        assertEquals(KeyResult.Act(StepStops(Back)), rtl.press(KeyCodes.VOLUME_UP))
        assertEquals(KeyResult.Consumed, rtl.onKey(KeyCodes.VOLUME_DOWN, pressed = false))
        val up = rtl.copy(volumeKeys = VolumeKeys.UpAdvances)
        assertEquals(KeyResult.Act(StepStops(Forward)), up.press(KeyCodes.VOLUME_UP))
        assertEquals(KeyResult.Act(StepStops(Back)), up.press(KeyCodes.VOLUME_DOWN))
    }

    @Test
    fun volumeKeysOffGoToTheSystem() {
        val off = rtl.copy(volumeKeys = VolumeKeys.Off)
        assertEquals(KeyResult.NotHandled, off.press(KeyCodes.VOLUME_DOWN))
        assertEquals(KeyResult.NotHandled, off.onKey(KeyCodes.VOLUME_UP, pressed = false))
    }

    @Test
    fun otherKeysGoToTheSystem() {
        assertEquals(KeyResult.NotHandled, rtl.press(KeyEvent.KEYCODE_A))
    }

    @Test
    fun backClosesTheTopmostThingFirst() {
        assertEquals(DismissTarget.Guide, dismissTarget(autoPlaying = false, guideShown = true, panelOpen = true, opening = true))
        assertEquals(DismissTarget.Panel, dismissTarget(autoPlaying = false, guideShown = false, panelOpen = true, opening = true))
        assertEquals(DismissTarget.Opening, dismissTarget(autoPlaying = false, guideShown = false, panelOpen = false, opening = true))
        assertEquals(DismissTarget.Reader, dismissTarget(autoPlaying = false, guideShown = false, panelOpen = false, opening = false))
    }

    @Test
    fun backDuringAutoplayOnlyStopsIt() {
        assertEquals(DismissTarget.AutoPlay, dismissTarget(autoPlaying = true, guideShown = false, panelOpen = false, opening = false))
        assertEquals(DismissTarget.AutoPlay, dismissTarget(autoPlaying = true, guideShown = true, panelOpen = true, opening = false))
    }

    /** Keys through [StoppingKey] as the screen feeds it: a key that stops autoplay ends it. */
    private class Playing(var autoPlaying: Boolean = true) {
        val key = StoppingKey()

        fun down(code: Int, repeat: Boolean = false) = feed(code, down = true, repeat)

        fun up(code: Int) = feed(code, down = false, repeat = false)

        private fun feed(code: Int, down: Boolean, repeat: Boolean) = key.onKey(code, down, repeat, autoPlaying).also {
            if (it == KeyGate.StopAutoPlay) autoPlaying = false
        }
    }

    @Test
    fun theKeyThatStopsAutoplayDoesNothingElseUntilItIsReleased() {
        val playing = Playing()
        val held = listOf(
            playing.down(KeyCodes.PAGE_DOWN),
            playing.down(KeyCodes.PAGE_DOWN, repeat = true),
            playing.down(KeyCodes.PAGE_DOWN, repeat = true),
            playing.up(KeyCodes.PAGE_DOWN),
        )
        assertEquals(listOf(KeyGate.StopAutoPlay, KeyGate.Swallow, KeyGate.Swallow, KeyGate.Swallow), held)
        assertEquals(KeyGate.Pass, playing.down(KeyCodes.PAGE_DOWN))
        assertEquals(KeyGate.Pass, playing.up(KeyCodes.PAGE_DOWN))
    }

    @Test
    fun anotherKeyActsWhileTheStoppingKeyIsHeld() {
        val playing = Playing()
        playing.down(KeyCodes.VOLUME_DOWN)
        assertEquals(KeyGate.Pass, playing.down(KeyCodes.PAGE_DOWN))
        assertEquals(KeyGate.Swallow, playing.down(KeyCodes.VOLUME_DOWN, repeat = true))
    }

    @Test
    fun aNewPressOfTheStoppingKeyActsEvenWhenItsReleaseWasLost() {
        val playing = Playing()
        playing.down(KeyCodes.PAGE_DOWN)
        assertEquals(KeyGate.Pass, playing.down(KeyCodes.PAGE_DOWN))
    }

    @Test
    fun aReleaseThatStopsAutoplayLeavesTheNextPressAlone() {
        val playing = Playing()
        assertEquals(KeyGate.StopAutoPlay, playing.up(KeyCodes.PAGE_DOWN))
        assertEquals(KeyGate.Pass, playing.down(KeyCodes.PAGE_DOWN))
    }
}
