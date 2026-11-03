package io.github.yuubiz.hirari.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yuubiz.hirari.core.model.BreathSensitivity
import io.github.yuubiz.hirari.core.model.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What decides whether the microphone is on: the setting `breathControl` and its sensitivity, the permission, and
 * whether the reader shows a book in the foreground.
 */
data class BreathInput(
    val enabled: Boolean = false,
    val sensitivity: BreathSensitivity = BreathSensitivity.Medium,
    val granted: Boolean = false,
    val foreground: Boolean = false,
) {
    val listens: Boolean get() = enabled && granted && foreground
}

/** What 息での操作 shows at the top right of the reader. */
sealed interface BreathView {
    /** The setting is on but the app may not use the microphone, so nothing listens. */
    data object NoPermission : BreathView

    /** The microphone would not start or stopped giving sound, as when another app holds it. */
    data object Unavailable : BreathView

    data class Listening(val detector: BreathDetector) : BreathView
}

/** What the command 息で操作 does: it turns off what listens, and otherwise turns on, asking for the microphone first. */
enum class BreathToggle { TurnOff, TurnOn, AskPermission }

fun breathToggle(input: BreathInput): BreathToggle = when {
    input.enabled && input.granted -> BreathToggle.TurnOff
    input.granted -> BreathToggle.TurnOn
    else -> BreathToggle.AskPermission
}

/**
 * The microphone of 息での操作 for one reader (docs/spec.md section 4.11). It listens while [BreathInput.listens] and
 * stops as soon as it does not, and every start measures the room again. Each settled step goes to [onStep].
 */
class BreathSession(private val scope: CoroutineScope, private val microphone: Microphone, private val onStep: (Turn) -> Unit) {
    var input: BreathInput = BreathInput()
        private set

    /** `null` while the setting is off or the reader is not in the foreground. */
    var view: BreathView? by mutableStateOf(null)
        private set

    private var listening: Job? = null

    fun update(next: BreathInput) {
        input = next
        if (!next.listens) {
            listening?.cancel()
            listening = null
        }
        val shown = view
        view = when {
            !next.enabled || !next.foreground -> null
            !next.granted -> BreathView.NoPermission
            shown is BreathView.Listening -> BreathView.Listening(shown.detector.withSensitivity(next.sensitivity))
            shown == BreathView.Unavailable && listening != null -> shown
            else -> BreathView.Listening(BreathDetector(next.sensitivity))
        }
        if (next.listens && listening == null) {
            listening = scope.launch {
                microphone.windows().collect(::heard)
                view = BreathView.Unavailable
            }
        }
    }

    private fun heard(amplitude: Int) {
        val listening = view as? BreathView.Listening ?: return
        val next = listening.detector.next(amplitude)
        view = BreathView.Listening(next.detector)
        next.step?.let(onStep)
    }
}
