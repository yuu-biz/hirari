package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.VolumeKeys

/** The `android.view.KeyEvent` codes the reader handles, as plain numbers so the mapping stays off the framework. */
object KeyCodes {
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val SPACE = 62
    const val ENTER = 66
    const val DEL = 67
    const val MENU = 82
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val BUTTON_L1 = 102
    const val BUTTON_R1 = 103
    const val MOVE_HOME = 122
    const val MOVE_END = 123
    const val NUMPAD_ENTER = 160
}

sealed interface ReaderAction {
    /** Turn to the neighboring view. */
    data class TurnView(val turn: Turn) : ReaderAction

    /** Move to the neighboring stop, and to the neighboring view past the last one. */
    data class StepStops(val turn: Turn) : ReaderAction

    /** Shift the pairing; [forward] moves the anchor one page later. */
    data class Shift(val forward: Boolean) : ReaderAction
    data object First : ReaderAction
    data object Last : ReaderAction
    data object OpenMenu : ReaderAction

    /** Back and Del. [dismissTarget] says what closes. */
    data object Dismiss : ReaderAction
}

sealed interface KeyResult {
    /** The system gets the key, as with the volume keys when they are off. */
    data object NotHandled : KeyResult

    /** The reader takes the key without acting, as on the release of a key it acted on. */
    data object Consumed : KeyResult
    data class Act(val action: ReaderAction) : KeyResult
}

/**
 * The key bindings of docs/spec.md section 4.8 (キー操作). A repeat of a held key comes as another
 * press and turns again. [mirrored] is the `mirrorControls` setting. While a [ReaderSheet] is up, the
 * reader takes only the keys of [ReaderSheet.takes].
 */
data class KeyBindings(val direction: ReadingDirection, val mirrored: Boolean, val volumeKeys: VolumeKeys) {
    fun onKey(code: Int, pressed: Boolean, shift: Boolean = false, sheet: ReaderSheet? = null): KeyResult {
        if (sheet != null && !sheet.takes(code)) return KeyResult.NotHandled
        val action = actionFor(code, shift) ?: return KeyResult.NotHandled
        return if (pressed) KeyResult.Act(action) else KeyResult.Consumed
    }

    private fun actionFor(code: Int, shift: Boolean): ReaderAction? = when (code) {
        KeyCodes.DPAD_LEFT -> ReaderAction.TurnView(if (leftGoesForward) Turn.Forward else Turn.Back)
        KeyCodes.DPAD_RIGHT -> ReaderAction.TurnView(if (leftGoesForward) Turn.Back else Turn.Forward)
        KeyCodes.DPAD_UP -> ReaderAction.Shift(forward = false)
        KeyCodes.DPAD_DOWN -> ReaderAction.Shift(forward = true)
        KeyCodes.PAGE_DOWN, KeyCodes.ENTER, KeyCodes.NUMPAD_ENTER, KeyCodes.BUTTON_R1 -> ReaderAction.TurnView(Turn.Forward)
        KeyCodes.PAGE_UP, KeyCodes.BUTTON_L1 -> ReaderAction.TurnView(Turn.Back)
        KeyCodes.SPACE -> ReaderAction.TurnView(if (shift) Turn.Back else Turn.Forward)
        KeyCodes.MOVE_HOME -> ReaderAction.First
        KeyCodes.MOVE_END -> ReaderAction.Last
        KeyCodes.MENU -> ReaderAction.OpenMenu
        KeyCodes.BACK, KeyCodes.DEL -> ReaderAction.Dismiss
        KeyCodes.VOLUME_DOWN -> volumeStep(down = true)
        KeyCodes.VOLUME_UP -> volumeStep(down = false)
        else -> null
    }

    private val leftGoesForward: Boolean get() = (direction == ReadingDirection.RightToLeft) != mirrored

    private fun volumeStep(down: Boolean): ReaderAction? = when (volumeKeys) {
        VolumeKeys.Off -> null
        VolumeKeys.DownAdvances -> ReaderAction.StepStops(if (down) Turn.Forward else Turn.Back)
        VolumeKeys.UpAdvances -> ReaderAction.StepStops(if (down) Turn.Back else Turn.Forward)
    }
}

enum class DismissTarget { Guide, Panel, Opening, Reader }

/** What Back and Del close, first match wins. [DismissTarget.Reader] saves the position and closes the reader. */
fun dismissTarget(guideShown: Boolean, panelOpen: Boolean, opening: Boolean): DismissTarget = when {
    guideShown -> DismissTarget.Guide
    panelOpen -> DismissTarget.Panel
    opening -> DismissTarget.Opening
    else -> DismissTarget.Reader
}
