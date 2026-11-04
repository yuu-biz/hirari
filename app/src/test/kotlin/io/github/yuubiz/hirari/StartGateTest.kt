package io.github.yuubiz.hirari

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartGateTest {
    private var finished = 0
    private val gate = StartGate { finished++ }

    @Test
    fun homeThatSettledBeforeThePlanFinishesTheStartOnlyOnceTheFolderOverItSettles() {
        gate.settled("home", top = "home")
        gate.applied(top = "folder")
        assertEquals(0, finished)

        gate.settled("folder", top = "folder")
        gate.settled("reader", top = "reader")
        assertEquals(1, finished)
    }

    @Test
    fun goingBackFromAPlannedScreenThatNeverSettledFinishesOnTheSettledScreenBelow() {
        gate.settled("home", top = "home")
        gate.applied(top = "reader")
        gate.check(top = "home")
        assertEquals(1, finished)
    }

    @Test
    fun nothingFinishesBeforeThePlanIsApplied() {
        gate.settled("home", top = "home")
        assertEquals(0, finished)
    }

    @Test
    fun aStartThatShowedItsContentFinishesOnlyOnceTheAppStayedUpForAWhile() = runTest {
        val watch = StartWatch(backgroundScope) { finished++ }
        watch.settled()
        advanceTimeBy(StartWatch.SURVIVAL_MILLIS - 1)
        assertEquals("a death now would not count", 0, finished)
        advanceTimeBy(2)
        assertEquals(1, finished)
        watch.settled()
        watch.stopped(true)
        runCurrent()
        assertEquals("the start finished twice", 1, finished)
    }

    @Test
    fun aStartThatShowedItsContentFinishesAtOnceWhenTheActivityStops() = runTest {
        val watch = StartWatch(backgroundScope) { finished++ }
        watch.stopped(true)
        watch.stopped(false)
        watch.settled()
        runCurrent()
        assertEquals("a stop before the content showed finished the start", 0, finished)
        watch.stopped(true)
        runCurrent()
        assertEquals(1, finished)
    }

    @Test
    fun aStartThatDropsTheKeptReaderKeepsTheOtherKeptScreensAndLoadsReadersOpenedAfterIt() {
        val kept = KeptScreens(restoring = true)
        assertEquals(false, kept.loads("folder"))
        assertEquals(false, kept.loads("reader", reader = true))
        kept.decide(adopt = true, dropReaders = true)
        assertEquals(true, kept.loads("folder"))
        assertEquals("the reader that may have failed loaded", false, kept.loads("reader", reader = true))
        assertEquals(true, kept.loads("next reader", reader = true))
    }

    @Test
    fun aStartThatKeepsTheScreensLoadsTheKeptReader() {
        val kept = KeptScreens(restoring = true)
        kept.loads("reader", reader = true)
        kept.decide(adopt = true)
        assertEquals(true, kept.loads("reader", reader = true))
    }

    @Test
    fun anActivityWithNoKeptStateStartsEvenWhileTheProcessLives() {
        assertEquals(StartKind.Fresh, startKind(savedState = false, startInProcess = false))
        assertEquals(StartKind.Fresh, startKind(savedState = false, startInProcess = true))
    }

    @Test
    fun keptStateInANewProcessIsAStartAndInTheSameProcessIsNot() {
        assertEquals(StartKind.Restored, startKind(savedState = true, startInProcess = false))
        assertEquals(StartKind.Recreated, startKind(savedState = true, startInProcess = true))
    }
}
