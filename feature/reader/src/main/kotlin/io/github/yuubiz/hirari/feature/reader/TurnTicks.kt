package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.ScrollMotion
import io.github.yuubiz.hirari.core.ui.TurnHaptics

/**
 * Whether [event], which took the reader from [before] to [after], earns the tick of 「めくりで振動」 (docs/spec.md
 * section 4.8). Only what moves the reader to another view counts: a tap, a key, a command, a step of 自動再生, a
 * jump from the page bar, and a pull that turned. A view that changes because the book, the screen, or the
 * settings changed the layout under the reader does not. In 縦に連続 a step of the strip counts as a turn, and so
 * does a jump to another page; a drag or a glide does not.
 */
internal fun turnTicks(event: ReaderEvent, before: ReaderState, after: ReaderState): Boolean = when (event) {
    is ReaderEvent.Act, is ReaderEvent.GoTo, is ReaderEvent.PullEnded, is ReaderEvent.AutoPlayDue -> {
        val step = after.scroll?.motion?.takeIf { it is ScrollMotion.Move && it != before.scroll?.motion }
        after.config.turnHaptics && step != null || TurnHaptics.due(after.config.turnHaptics, before.current, after.current)
    }
    else -> false
}
