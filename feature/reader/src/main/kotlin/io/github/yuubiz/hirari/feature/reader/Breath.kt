package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BreathSensitivity
import io.github.yuubiz.hirari.core.model.Turn

/** The microphone of 息での操作 (docs/spec.md section 4.11): 8 kHz mono, judged in windows of 100 ms. */
const val BREATH_SAMPLE_RATE = 8000
const val BREATH_WINDOW_MS = 100
const val BREATH_WINDOW_SAMPLES = BREATH_SAMPLE_RATE * BREATH_WINDOW_MS / 1000

/** The 2 seconds of ambient sound that set the base A. */
const val CALIBRATION_WINDOWS = 20

/**
 * The thresholds of one sensitivity over the base [ambient]. A window at or above [sound] is sound, one below
 * [silence] is silence, and one between keeps the state before it. A short sound is settled after [settleWindows] of
 * silence, and a sound of more than [longWindows] is long.
 */
data class BreathThresholds(val ambient: Int, val sound: Int, val silence: Int, val settleWindows: Int, val longWindows: Int) {
    companion object {
        fun of(sensitivity: BreathSensitivity, ambient: Int): BreathThresholds = when (sensitivity) {
            BreathSensitivity.Low -> BreathThresholds(ambient, maxOf(ambient * 6, 2000), maxOf(ambient * 3, 1000), 4, 12)
            BreathSensitivity.Medium -> BreathThresholds(ambient, maxOf(ambient * 4, 1500), maxOf(ambient * 2, 800), 3, 8)
            BreathSensitivity.High -> BreathThresholds(ambient, maxOf(ambient * 3, 1000), maxOf(ambient * 3 / 2, 500), 2, 4)
        }
    }
}

/** Where the judgment of the windows stands. */
sealed interface BreathPhase {
    /** The first windows after the microphone starts, which measure the room. */
    data class Calibrating(val heard: List<Int>) : BreathPhase

    data object Quiet : BreathPhase

    /** A sound of [windows] so far, after [blowsBefore] short sounds that wait to be settled. */
    data class Blowing(val windows: Int, val blowsBefore: Int) : BreathPhase

    /** [blows] short sounds, and [silentWindows] of silence after the last one. */
    data class Settling(val blows: Int, val silentWindows: Int) : BreathPhase

    /** A long sound, which scrolls toward [turn] while it lasts. */
    data class Scrolling(val turn: Turn) : BreathPhase
}

/** The judgment after one window, and the step it settled, if any. */
data class BreathStep(val detector: BreathDetector, val step: Turn?)

/**
 * The judgment of 息での操作 over the maximum amplitudes of successive windows (docs/spec.md section 4.11). One short
 * sound steps forward and two or more step back, once the silence after the last one settles it. A long sound
 * scrolls while it lasts, forward and back in turn. Windows are the only clock.
 */
data class BreathDetector(
    val sensitivity: BreathSensitivity,
    val phase: BreathPhase = BreathPhase.Calibrating(emptyList()),
    /** Known once calibration ends. */
    val thresholds: BreathThresholds? = null,
    /** The direction of the next long sound. */
    val nextLong: Turn = Turn.Forward,
    /** The last window's amplitude, for the meter on screen. */
    val level: Int = 0,
) {
    val scrolling: Turn? get() = (phase as? BreathPhase.Scrolling)?.turn

    /** The same judgment under [next], keeping the base A it measured. */
    fun withSensitivity(next: BreathSensitivity): BreathDetector =
        copy(sensitivity = next, thresholds = thresholds?.let { BreathThresholds.of(next, it.ambient) })

    fun next(amplitude: Int): BreathStep {
        val heard = copy(level = amplitude)
        val limits = thresholds
        val phase = phase
        if (phase is BreathPhase.Calibrating || limits == null) return BreathStep(heard.calibrated(amplitude), null)
        val wasSound = phase is BreathPhase.Blowing || phase is BreathPhase.Scrolling
        val sound = when {
            amplitude >= limits.sound -> true
            amplitude < limits.silence -> false
            else -> wasSound
        }
        return when (phase) {
            BreathPhase.Quiet -> BreathStep(if (sound) heard.copy(phase = BreathPhase.Blowing(1, 0)) else heard, null)
            is BreathPhase.Blowing -> when {
                !sound -> heard.settling(phase.blowsBefore + 1, 1, limits)
                phase.windows + 1 > limits.longWindows ->
                    BreathStep(heard.copy(phase = BreathPhase.Scrolling(nextLong), nextLong = nextLong.opposite), null)
                else -> BreathStep(heard.copy(phase = phase.copy(windows = phase.windows + 1)), null)
            }
            is BreathPhase.Settling ->
                if (sound) BreathStep(heard.copy(phase = BreathPhase.Blowing(1, phase.blows)), null) else heard.settling(phase.blows, phase.silentWindows + 1, limits)
            is BreathPhase.Scrolling -> BreathStep(if (sound) heard else heard.copy(phase = BreathPhase.Quiet), null)
            is BreathPhase.Calibrating -> error("handled above")
        }
    }

    private fun calibrated(amplitude: Int): BreathDetector {
        val heard = (phase as? BreathPhase.Calibrating)?.heard.orEmpty() + amplitude
        if (heard.size < CALIBRATION_WINDOWS) return copy(phase = BreathPhase.Calibrating(heard))
        return copy(phase = BreathPhase.Quiet, thresholds = BreathThresholds.of(sensitivity, median(heard)))
    }

    private fun settling(blows: Int, silent: Int, limits: BreathThresholds): BreathStep {
        if (silent < limits.settleWindows) return BreathStep(copy(phase = BreathPhase.Settling(blows, silent)), null)
        return BreathStep(copy(phase = BreathPhase.Quiet), if (blows == 1) Turn.Forward else Turn.Back)
    }
}

private val Turn.opposite: Turn get() = if (this == Turn.Forward) Turn.Back else Turn.Forward

private fun median(values: List<Int>): Int {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}
