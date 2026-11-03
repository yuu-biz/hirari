package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BreathSensitivity
import io.github.yuubiz.hirari.core.model.Turn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A microphone that hears what the test sends, and counts how often it is on. */
private class FakeMicrophone : Microphone {
    var sound = Channel<Int>(Channel.UNLIMITED)
    var on = 0
    var starts = 0

    override fun windows(): Flow<Int> {
        sound = Channel(Channel.UNLIMITED)
        return sound.consumeAsFlow().onStart {
            on++
            starts++
        }.onCompletion { on-- }
    }

    fun hear(vararg windows: Int) = windows.forEach { sound.trySend(it) }
}

/** A microphone that is slow to let go: after it is stopped it stays held until the test lets it go. */
private class HeldMicrophone : Microphone {
    var held = 0
    var peak = 0
    var starts = 0
    private val lettingGo = ArrayDeque<CompletableDeferred<Unit>>()

    override fun windows(): Flow<Int> = flow {
        starts++
        held++
        peak = maxOf(peak, held)
        val release = CompletableDeferred<Unit>().also(lettingGo::addLast)
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                release.await()
                held--
            }
        }
    }

    fun letGo() = lettingGo.removeFirstOrNull()?.complete(Unit)
}

private val READING =BreathInput(enabled = true, sensitivity = BreathSensitivity.Medium, granted = true, foreground = true)
private const val LOUD = 20000
private const val QUIET = 50

@OptIn(ExperimentalCoroutinesApi::class)
class BreathSessionTest {
    @Test
    fun theMicrophoneIsOffByDefault() = runTest(UnconfinedTestDispatcher()) {
        val mic = FakeMicrophone()
        val session = BreathSession(backgroundScope, mic) {}
        session.update(READING.copy(enabled = false))
        assertEquals(0, mic.starts)
        assertNull(session.view)
    }

    @Test
    fun withoutThePermissionNothingListensAndTheCornerSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val mic = FakeMicrophone()
        val session = BreathSession(backgroundScope, mic) {}
        session.update(READING.copy(granted = false))
        assertEquals(0, mic.starts)
        assertEquals(BreathView.NoPermission, session.view)
    }

    @Test
    fun leavingTheForegroundStopsTheMicrophoneAndComingBackStartsItAgain() = runTest(UnconfinedTestDispatcher()) {
        val mic = FakeMicrophone()
        val session = BreathSession(backgroundScope, mic) {}
        session.update(READING)
        assertEquals(1, mic.on)
        session.update(READING.copy(foreground = false))
        assertEquals(0, mic.on)
        assertNull(session.view)
        session.update(READING)
        assertEquals(1, mic.on)
        assertEquals(2, mic.starts)
    }

    @Test
    fun theMicrophoneStartsAgainOnlyAfterTheLastOneLetGo() = runTest(UnconfinedTestDispatcher()) {
        val mic = HeldMicrophone()
        val session = BreathSession(backgroundScope, mic) {}
        session.update(READING)
        session.update(READING.copy(foreground = false))
        session.update(READING)
        assertEquals(1, mic.starts)
        assertEquals(1, mic.held)
        mic.letGo()
        assertEquals(2, mic.starts)
        assertEquals(1, mic.held)
        assertEquals(1, mic.peak)
    }

    @Test
    fun aQuickPauseAndResumeAgainStartsOnceAfterEveryRelease() = runTest(UnconfinedTestDispatcher()) {
        val mic = HeldMicrophone()
        val session = BreathSession(backgroundScope, mic) {}
        session.update(READING)
        repeat(3) {
            session.update(READING.copy(foreground = false))
            session.update(READING)
        }
        assertEquals(1, mic.starts)
        mic.letGo()
        assertEquals(2, mic.starts)
        assertEquals(1, mic.peak)
        assertEquals(1, mic.held)
    }

    @Test
    fun aMicrophoneNotWantedAnyMoreIsNotStartedAfterTheWait() = runTest(UnconfinedTestDispatcher()) {
        val mic = HeldMicrophone()
        val session = BreathSession(backgroundScope, mic) {}
        session.update(READING)
        session.update(READING.copy(foreground = false))
        session.update(READING)
        session.update(READING.copy(foreground = false))
        mic.letGo()
        assertEquals(1, mic.starts)
        assertEquals(0, mic.held)
    }

    @Test
    fun turningTheSettingOffStopsTheMicrophone() = runTest(UnconfinedTestDispatcher()) {
        val mic = FakeMicrophone()
        val session = BreathSession(backgroundScope, mic) {}
        session.update(READING)
        session.update(READING.copy(enabled = false))
        assertEquals(0, mic.on)
    }

    @Test
    fun aShortBlowAfterCalibrationStepsForward() = runTest(UnconfinedTestDispatcher()) {
        val mic = FakeMicrophone()
        val steps = mutableListOf<Turn>()
        val session = BreathSession(backgroundScope, mic, steps::add)
        session.update(READING)
        mic.hear(*IntArray(CALIBRATION_WINDOWS) { QUIET }, LOUD, QUIET, QUIET, QUIET)
        assertEquals(listOf(Turn.Forward), steps)
    }

    @Test
    fun everyStartMeasuresTheRoomAgain() = runTest(UnconfinedTestDispatcher()) {
        val mic = FakeMicrophone()
        val steps = mutableListOf<Turn>()
        val session = BreathSession(backgroundScope, mic, steps::add)
        session.update(READING)
        mic.hear(*IntArray(CALIBRATION_WINDOWS) { QUIET })
        session.update(READING.copy(foreground = false))
        session.update(READING)
        val view = assertIs<BreathView.Listening>(session.view)
        assertIs<BreathPhase.Calibrating>(view.detector.phase)
        mic.hear(LOUD, QUIET, QUIET, QUIET)
        assertEquals(emptyList(), steps)
    }

    @Test
    fun aNewSensitivityKeepsListeningWithoutStartingAgain() = runTest(UnconfinedTestDispatcher()) {
        val mic = FakeMicrophone()
        val session = BreathSession(backgroundScope, mic) {}
        session.update(READING)
        mic.hear(*IntArray(CALIBRATION_WINDOWS) { 1000 })
        session.update(READING.copy(sensitivity = BreathSensitivity.High))
        assertEquals(1, mic.starts)
        val view = assertIs<BreathView.Listening>(session.view)
        assertEquals(BreathThresholds.of(BreathSensitivity.High, 1000), view.detector.thresholds)
    }

    @Test
    fun aMicrophoneThatGivesNoSoundIsShownAsUnavailable() = runTest(UnconfinedTestDispatcher()) {
        val session = BreathSession(backgroundScope, { emptyFlow() }) {}
        session.update(READING)
        assertEquals(BreathView.Unavailable, session.view)
        session.update(READING.copy(sensitivity = BreathSensitivity.Low))
        assertEquals(BreathView.Unavailable, session.view)
    }

    @Test
    fun theCommandTurnsOffWhatListensAndAsksBeforeTurningOn() {
        assertEquals(BreathToggle.TurnOff, breathToggle(READING))
        assertEquals(BreathToggle.TurnOn, breathToggle(READING.copy(enabled = false)))
        assertEquals(BreathToggle.AskPermission, breathToggle(READING.copy(enabled = false, granted = false)))
        assertEquals(BreathToggle.AskPermission, breathToggle(READING.copy(granted = false)))
    }
}
