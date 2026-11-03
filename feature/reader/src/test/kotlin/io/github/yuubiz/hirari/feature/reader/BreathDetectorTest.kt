package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BreathSensitivity
import io.github.yuubiz.hirari.core.model.Turn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** What the detector settles over [windows], with the detector at the end. */
private data class Heard(val steps: List<Turn>, val detector: BreathDetector)

private fun BreathDetector.hear(vararg windows: Int): Heard {
    var detector = this
    val steps = mutableListOf<Turn>()
    for (window in windows) {
        val next = detector.next(window)
        detector = next.detector
        next.step?.let(steps::add)
    }
    return Heard(steps, detector)
}

/** A detector of [sensitivity] calibrated in a room of [ambient]. */
private fun calibrated(sensitivity: BreathSensitivity = BreathSensitivity.Medium, ambient: Int = 100): BreathDetector =
    BreathDetector(sensitivity).hear(*IntArray(CALIBRATION_WINDOWS) { ambient }).detector

private fun repeat(times: Int, amplitude: Int) = IntArray(times) { amplitude }

private const val LOUD = 20000
private const val QUIET = 50

class BreathDetectorTest {
    @Test
    fun theWindowIsOneTenthOfASecondAt8kHz() {
        assertEquals(800, BREATH_WINDOW_SAMPLES)
    }

    @Test
    fun twoSecondsOfTheRoomSetTheBase() {
        val almost = BreathDetector(BreathSensitivity.Medium).hear(*repeat(CALIBRATION_WINDOWS - 1, 300))
        assertIs<BreathPhase.Calibrating>(almost.detector.phase)
        assertNull(almost.detector.thresholds)
        val done = almost.detector.hear(300).detector
        assertEquals(BreathPhase.Quiet, done.phase)
        assertEquals(300, done.thresholds?.ambient)
    }

    @Test
    fun soundDuringCalibrationIsMeasuredNotJudged() {
        val heard = BreathDetector(BreathSensitivity.Medium).hear(*repeat(CALIBRATION_WINDOWS, LOUD))
        assertEquals(emptyList(), heard.steps)
    }

    @Test
    fun oneLoudWindowDuringCalibrationDoesNotRaiseTheBase() {
        val windows = repeat(CALIBRATION_WINDOWS, 200).also { it[5] = 30000 }
        assertEquals(200, BreathDetector(BreathSensitivity.Medium).hear(*windows).detector.thresholds?.ambient)
    }

    @Test
    fun theThresholdsFollowTheTableAboveTheirFloors() {
        assertEquals(BreathThresholds(1000, 6000, 3000, 4, 12), BreathThresholds.of(BreathSensitivity.Low, 1000))
        assertEquals(BreathThresholds(1000, 4000, 2000, 3, 8), BreathThresholds.of(BreathSensitivity.Medium, 1000))
        assertEquals(BreathThresholds(1000, 3000, 1500, 2, 4), BreathThresholds.of(BreathSensitivity.High, 1000))
    }

    @Test
    fun aQuietRoomGetsTheFloors() {
        assertEquals(BreathThresholds(10, 2000, 1000, 4, 12), BreathThresholds.of(BreathSensitivity.Low, 10))
        assertEquals(BreathThresholds(10, 1500, 800, 3, 8), BreathThresholds.of(BreathSensitivity.Medium, 10))
        assertEquals(BreathThresholds(10, 1000, 500, 2, 4), BreathThresholds.of(BreathSensitivity.High, 10))
    }

    @Test
    fun aShortSoundStepsForwardOnTheLastWindowOfSilence() {
        val blown = calibrated().hear(LOUD, LOUD, QUIET, QUIET)
        assertEquals(emptyList(), blown.steps)
        val settled = blown.detector.hear(QUIET)
        assertEquals(listOf(Turn.Forward), settled.steps)
        assertEquals(BreathPhase.Quiet, settled.detector.phase)
    }

    @Test
    fun eachSensitivityWaitsItsOwnSilence() {
        for ((sensitivity, windows) in listOf(BreathSensitivity.Low to 4, BreathSensitivity.Medium to 3, BreathSensitivity.High to 2)) {
            val detector = calibrated(sensitivity)
            assertEquals(emptyList(), detector.hear(LOUD, *repeat(windows - 1, QUIET)).steps, "$sensitivity")
            assertEquals(listOf(Turn.Forward), detector.hear(LOUD, *repeat(windows, QUIET)).steps, "$sensitivity")
        }
    }

    @Test
    fun theSoundThresholdCountsAndTheSilenceThresholdDoesNot() {
        val detector = calibrated(ambient = 10)
        assertEquals(listOf(Turn.Forward), detector.hear(1500, 799, 799, 799).steps)
        assertEquals(emptyList(), detector.hear(1499, 799, 799, 799).steps)
        assertEquals(emptyList(), detector.hear(1500, 800, 799, 799).steps)
    }

    @Test
    fun aWindowBetweenTheThresholdsKeepsTheStateBefore() {
        val detector = calibrated(ambient = 10)
        val between = 1000
        assertEquals(BreathPhase.Quiet, detector.hear(between, between, between).detector.phase)
        val held = detector.hear(LOUD, between, between)
        assertEquals(BreathPhase.Blowing(3, 0), held.detector.phase)
        assertEquals(listOf(Turn.Forward), detector.hear(LOUD, QUIET, between, between).steps)
    }

    @Test
    fun aSoundThatDipsBetweenTheThresholdsStaysOneSound() {
        val detector = calibrated(ambient = 10)
        assertEquals(listOf(Turn.Forward), detector.hear(LOUD, 1000, LOUD, 1000, LOUD, QUIET, QUIET, QUIET).steps)
    }

    @Test
    fun aSecondSoundBeforeTheFirstSettlesStepsBack() {
        val detector = calibrated()
        val twice = detector.hear(LOUD, QUIET, QUIET, LOUD, LOUD, QUIET, QUIET)
        assertEquals(emptyList(), twice.steps)
        assertEquals(listOf(Turn.Back), twice.detector.hear(QUIET).steps)
    }

    @Test
    fun aSecondSoundAfterTheFirstSettledIsAnotherStepForward() {
        assertEquals(listOf(Turn.Forward, Turn.Forward), calibrated().hear(LOUD, QUIET, QUIET, QUIET, LOUD, QUIET, QUIET, QUIET).steps)
    }

    @Test
    fun threeQuickSoundsStepBackOnce() {
        assertEquals(listOf(Turn.Back), calibrated().hear(LOUD, QUIET, LOUD, QUIET, LOUD, QUIET, QUIET, QUIET).steps)
    }

    @Test
    fun aSoundOfExactlyTheLongLengthIsShort() {
        val heard = calibrated().hear(*repeat(8, LOUD), QUIET, QUIET, QUIET)
        assertEquals(listOf(Turn.Forward), heard.steps)
    }

    @Test
    fun aSoundPastTheLongLengthScrollsUntilSilenceAndStepsNothing() {
        val detector = calibrated()
        val long = detector.hear(*repeat(9, LOUD))
        assertEquals(Turn.Forward, long.detector.scrolling)
        val held = long.detector.hear(*repeat(30, LOUD))
        assertEquals(Turn.Forward, held.detector.scrolling)
        val ended = held.detector.hear(QUIET, QUIET, QUIET, QUIET)
        assertNull(ended.detector.scrolling)
        assertEquals(emptyList(), long.steps + held.steps + ended.steps)
    }

    @Test
    fun longSoundsScrollForwardAndBackInTurn() {
        val first = calibrated().hear(*repeat(9, LOUD), QUIET).detector
        val second = first.hear(*repeat(9, LOUD))
        assertEquals(Turn.Back, second.detector.scrolling)
        val third = second.detector.hear(QUIET, *repeat(9, LOUD))
        assertEquals(Turn.Forward, third.detector.scrolling)
    }

    @Test
    fun aLongSoundDropsTheShortSoundWaitingBeforeIt() {
        val heard = calibrated().hear(LOUD, QUIET, *repeat(9, LOUD), QUIET, QUIET, QUIET)
        assertEquals(emptyList(), heard.steps)
    }

    @Test
    fun noiseBelowTheSoundThresholdNeverSteps() {
        val detector = calibrated(ambient = 300)
        val noise = IntArray(200) { if (it % 3 == 0) 1199 else 700 }
        assertEquals(emptyList(), detector.hear(*noise).steps)
    }

    @Test
    fun aLoudRoomRaisesTheThresholds() {
        val noisy = calibrated(ambient = 2000)
        assertEquals(emptyList(), noisy.hear(7999, QUIET, QUIET, QUIET).steps)
        assertEquals(listOf(Turn.Forward), noisy.hear(8000, QUIET, QUIET, QUIET).steps)
    }

    @Test
    fun aNewSensitivityKeepsTheBase() {
        val detector = calibrated(BreathSensitivity.Medium, ambient = 1000).withSensitivity(BreathSensitivity.High)
        assertEquals(BreathThresholds.of(BreathSensitivity.High, 1000), detector.thresholds)
        assertEquals(listOf(Turn.Forward), detector.hear(3000, QUIET, QUIET).steps)
    }

    @Test
    fun theMeterShowsTheLastWindow() {
        assertEquals(1234, calibrated().hear(LOUD, 1234).detector.level)
    }
}
