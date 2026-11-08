package io.github.yuubiz.hirari.core.image.thumbnail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrencyTest {
    @Test
    fun raisingTheLimitStartsTwoMoreOfSixWaitersInCurrentUrgencyOrder() = runTest {
        val gate = PriorityGate(2)
        val release = CompletableDeferred<Unit>()
        repeat(2) { launch { gate.withPermit(Urgency(ThumbPriority.Visible)) { release.await() } } }
        runCurrent()
        val order = ArrayList<Int>()
        val distances = mutableListOf(5, 4, 3, 2, 1, 0)
        repeat(6) { index ->
            launch {
                gate.withPermit(Urgency(ThumbPriority.Visible) { distances[index] }) {
                    order += index
                    release.await()
                }
            }
        }
        runCurrent()
        distances[0] = 0
        gate.setPermits(4)
        runCurrent()
        assertEquals(listOf(0, 5), order)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(0, 5, 4, 3, 2, 1), order)
    }

    @Test
    fun loweringTheLimitLetsAllFourFinishAndWaitsUntilFewerThanTwoRun() = runTest {
        val gate = PriorityGate(4)
        val releases = List(4) { CompletableDeferred<Unit>() }
        val running = releases.map { release ->
            launch { gate.withPermit(Urgency(ThumbPriority.Visible)) { release.await() } }
        }
        runCurrent()
        val releaseNext = CompletableDeferred<Unit>()
        var nextStarted = false
        launch {
            gate.withPermit(Urgency(ThumbPriority.Visible)) {
                nextStarted = true
                releaseNext.await()
            }
        }
        runCurrent()
        gate.setPermits(2)
        runCurrent()
        assertTrue(running.all { it.isActive && !it.isCancelled })
        assertFalse(nextStarted)
        repeat(2) { index ->
            releases[index].complete(Unit)
            runCurrent()
            assertFalse(nextStarted)
        }
        releases[2].complete(Unit)
        runCurrent()
        assertTrue(nextStarted)
        assertTrue(running.none { it.isCancelled })
        releases[3].complete(Unit)
        releaseNext.complete(Unit)
    }

    @Test
    fun cancellationAfterResizeHandsTheGrantedPermitToTheNextWaiter() = runTest {
        val gate = PriorityGate(1)
        val release = CompletableDeferred<Unit>()
        launch { gate.withPermit(Urgency(ThumbPriority.Visible)) { release.await() } }
        runCurrent()
        val cancelled = launch { gate.withPermit(Urgency(ThumbPriority.Visible)) { error("cancelled waiter ran") } }
        var started = false
        launch {
            gate.withPermit(Urgency(ThumbPriority.Visible)) {
                started = true
                release.await()
            }
        }
        runCurrent()
        gate.setPermits(2)
        cancelled.cancel()
        runCurrent()
        assertTrue(started)
        release.complete(Unit)
        runCurrent()
        var running = 0
        val finish = CompletableDeferred<Unit>()
        repeat(3) {
            launch {
                gate.withPermit(Urgency(ThumbPriority.Visible)) {
                    running++
                    finish.await()
                    running--
                }
            }
        }
        runCurrent()
        assertEquals(2, running)
        finish.complete(Unit)
    }

    private fun TestScope.workScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test
    fun callersForTheSameKeyShareOneRun() = runTest {
        val flights = Flights<String, Int>(workScope())
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val results = List(3) {
            val result = CompletableDeferred<Int>()
            launch {
                result.complete(
                    flights.join("a", Urgency(ThumbPriority.Visible)) {
                        runs++
                        release.await()
                        7
                    },
                )
            }
            result
        }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(1, runs)
        assertEquals(listOf(7, 7, 7), results.map { it.getCompleted() })
    }

    @Test
    fun theRunStopsOnlyWhenTheLastCallerGivesUp() = runTest {
        val flights = Flights<String, Int>(workScope())
        var cancelled = false
        val work: suspend (Urgency) -> Int = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val first = launch { flights.join("a", Urgency(ThumbPriority.Visible), work) }
        val second = launch { flights.join("a", Urgency(ThumbPriority.Visible), work) }
        runCurrent()
        first.cancel()
        runCurrent()
        assertFalse("the run stopped while a caller still waited", cancelled)
        second.cancel()
        runCurrent()
        assertTrue("the run went on with nobody waiting", cancelled)
    }

    @Test
    fun aCallerAfterTheLastOneGaveUpStartsANewRun() = runTest {
        val flights = Flights<String, Int>(workScope())
        var runs = 0
        val first = launch {
            flights.join("a", Urgency(ThumbPriority.Visible)) {
                runs++
                awaitCancellation()
            }
        }
        runCurrent()
        first.cancel()
        runCurrent()
        val second = CompletableDeferred<Int>()
        launch {
            second.complete(
                flights.join("a", Urgency(ThumbPriority.Visible)) {
                    runs++
                    5
                },
            )
        }
        runCurrent()
        assertEquals(2, runs)
        assertEquals(5, second.getCompleted())
    }

    /** Work that, once cancelled, takes until [finishing] completes to end. */
    private fun slowToStop(started: MutableList<String>, finishing: CompletableDeferred<Unit>): suspend (Urgency) -> Int = {
        started += "old"
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { finishing.await() }
        }
    }

    @Test
    fun aReplacementWaitsForEveryEarlierRunEvenWhenTheOneBetweenWasStoppedBeforeItStarted() = runTest {
        val flights = Flights<String, Int>(workScope())
        val started = ArrayList<String>()
        val finishing = CompletableDeferred<Unit>()
        launch { flights.join("a", Urgency(ThumbPriority.Visible), slowToStop(started, finishing)) }
        runCurrent()
        val middle = async { flights.replace("a", Urgency(ThumbPriority.Visible)) { started += "middle"; 2 } }
        val last = async { flights.replace("a", Urgency(ThumbPriority.Visible)) { started += "last"; 3 } }
        runCurrent()
        assertEquals("nothing starts while the first run ends", listOf("old"), started)
        finishing.complete(Unit)
        runCurrent()
        assertEquals(listOf("old", "last"), started)
        assertEquals(3, middle.await())
        assertEquals(3, last.await())
    }

    @Test
    fun aReplacementWaitsForARunThatNobodyWaitsForAnyMoreUntilItEnds() = runTest {
        val flights = Flights<String, Int>(workScope())
        val started = ArrayList<String>()
        val finishing = CompletableDeferred<Unit>()
        val first = launch { flights.join("a", Urgency(ThumbPriority.Visible), slowToStop(started, finishing)) }
        runCurrent()
        first.cancel()
        runCurrent()
        val next = async { flights.replace("a", Urgency(ThumbPriority.Visible)) { started += "new"; 2 } }
        runCurrent()
        assertEquals("nothing starts while the first run ends", listOf("old"), started)
        finishing.complete(Unit)
        runCurrent()
        assertEquals(listOf("old", "new"), started)
        assertEquals(2, next.await())
    }

    @Test
    fun theGateRunsAsManyAsItHasPermitsAndTheRestWait() = runTest {
        val gate = PriorityGate(2)
        val release = CompletableDeferred<Unit>()
        var running = 0
        var most = 0
        repeat(5) {
            launch {
                gate.withPermit(Urgency(ThumbPriority.Visible)) {
                    running++
                    most = maxOf(most, running)
                    release.await()
                    running--
                }
            }
        }
        runCurrent()
        assertEquals(2, running)
        release.complete(Unit)
        runCurrent()
        assertEquals(0, running)
        assertEquals(2, most)
    }

    @Test
    fun aFreedPermitGoesToTheBestPriorityThenToTheOneThatCameFirst() = runTest {
        val gate = PriorityGate(1)
        val release = CompletableDeferred<Unit>()
        val order = ArrayList<String>()
        launch { gate.withPermit(Urgency(ThumbPriority.Visible)) { release.await() } }
        runCurrent()
        for ((name, priority) in listOf("near1" to ThumbPriority.Nearby, "near2" to ThumbPriority.Nearby, "visible" to ThumbPriority.Visible)) {
            launch { gate.withPermit(Urgency(priority)) { order += name } }
            runCurrent()
        }
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("visible", "near1", "near2"), order)
    }

    @Test
    fun aWaiterThatIsCancelledGivesUpItsPlaceWithoutLosingAPermit() = runTest {
        val gate = PriorityGate(1)
        val release = CompletableDeferred<Unit>()
        launch { gate.withPermit(Urgency(ThumbPriority.Visible)) { release.await() } }
        runCurrent()
        val waiting = launch { gate.withPermit(Urgency(ThumbPriority.Visible)) { error("a cancelled waiter ran") } }
        runCurrent()
        waiting.cancel()
        release.complete(Unit)
        runCurrent()
        var ran = false
        launch { gate.withPermit(Urgency(ThumbPriority.Visible)) { ran = true } }
        runCurrent()
        assertTrue("the permit of the cancelled waiter was lost", ran)
    }

    @Test
    fun anUrgencyIsTheBestOfTheOnesItFollowsNow() {
        val flight = Urgency.borrowed()
        val lent = Urgency.borrowed().also { it.follow(flight) }
        val nearby = Urgency(ThumbPriority.Nearby)
        val visible = Urgency(ThumbPriority.Visible)
        flight.follow(nearby)
        assertEquals(nearby.rank, lent.rank)
        flight.follow(visible)
        assertEquals(visible.rank, lent.rank)
        flight.unfollow(visible)
        assertEquals(nearby.rank, lent.rank)
        flight.unfollow(nearby)
        assertEquals(Long.MAX_VALUE, lent.rank)
    }

    @Test
    fun aFreedPermitGoesToTheWaiterNearestTheMiddleAsItIsWhenThePermitIsFree() = runTest {
        val gate = PriorityGate(1)
        val release = CompletableDeferred<Unit>()
        val order = ArrayList<String>()
        val distances = mutableMapOf("top" to 1, "bottom" to 3, "far nearby" to 9, "near nearby" to 2)
        launch { gate.withPermit(Urgency(ThumbPriority.Visible)) { release.await() } }
        runCurrent()
        for (name in listOf("top", "bottom")) {
            launch { gate.withPermit(Urgency(ThumbPriority.Visible) { distances.getValue(name) }) { order += name } }
        }
        for (name in listOf("far nearby", "near nearby")) {
            launch { gate.withPermit(Urgency(ThumbPriority.Nearby) { distances.getValue(name) }) { order += name } }
        }
        runCurrent()
        distances["top"] = 5
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("bottom", "top", "near nearby", "far nearby"), order)
    }
}
