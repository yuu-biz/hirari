package io.github.yuubiz.hirari

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
