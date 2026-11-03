package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.KeyBindings
import io.github.yuubiz.hirari.core.model.KeyResult

/** The key bindings while [sheet] is up: the reader takes only the keys of [ReaderSheet.takes]. */
fun KeyBindings.onKey(code: Int, pressed: Boolean, shift: Boolean = false, sheet: ReaderSheet?): KeyResult =
    if (sheet != null && !sheet.takes(code)) KeyResult.NotHandled else onKey(code, pressed, shift)

/** What [StoppingKey] lets a key do. */
enum class KeyGate {
    /** Autoplay runs, so the key stops it and does nothing else. */
    StopAutoPlay,

    /** A repeat or the release of the key that stopped autoplay. */
    Swallow,

    /** The key goes to the bindings. */
    Pass,
}

/**
 * The key that stopped autoplay does nothing else (docs/spec.md section 4.8, 自動再生): its repeats and its release
 * are swallowed. A new press of it acts, so a release that never came does not leave the key dead. Other keys act
 * while it is held.
 */
class StoppingKey {
    private var held: Int? = null

    /** A [repeat] comes as another [down] with a repeat count above 0. */
    fun onKey(code: Int, down: Boolean, repeat: Boolean, autoPlaying: Boolean): KeyGate {
        if (autoPlaying) {
            held = code.takeIf { down }
            return KeyGate.StopAutoPlay
        }
        if (code != held) return KeyGate.Pass
        if (down && !repeat) {
            held = null
            return KeyGate.Pass
        }
        if (!down) held = null
        return KeyGate.Swallow
    }
}

enum class DismissTarget { AutoPlay, Guide, Panel, Opening, Reader }

/**
 * What Back and Del close, first match wins: the back key, the system's back gesture and Del all ask here. During
 * autoplay they only stop it. [DismissTarget.Reader] saves the position and closes the reader.
 */
fun dismissTarget(autoPlaying: Boolean, guideShown: Boolean, panelOpen: Boolean, opening: Boolean): DismissTarget = when {
    autoPlaying -> DismissTarget.AutoPlay
    guideShown -> DismissTarget.Guide
    panelOpen -> DismissTarget.Panel
    opening -> DismissTarget.Opening
    else -> DismissTarget.Reader
}
