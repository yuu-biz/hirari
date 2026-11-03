package io.github.yuubiz.hirari.core.ui

import android.view.HapticFeedbackConstants
import android.view.View

/** The decision of 「めくりで振動」 (docs/spec.md section 4.10), which both readers share. */
object TurnHaptics {
    /**
     * Whether a turn from the view [before] to the view [after] earns a tick: the setting is on and the turn
     * really changed the view. A turn that ran into an end of the book, or landed on the view it left, does not.
     * Views are told apart by whatever each reader names a view with.
     */
    fun <V : Any> due(enabled: Boolean, before: V, after: V): Boolean = enabled && before != after
}

/**
 * The tick of 「めくりで振動」. It goes through the view's own haptic feedback with no flags, so the system's touch
 * feedback setting and the view's [View.isHapticFeedbackEnabled] decide whether it is felt. The system picks how
 * long it is.
 */
fun View.performTurnHaptic(): Boolean = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
