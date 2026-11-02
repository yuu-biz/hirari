package io.github.yuubiz.hirari.core.image.thumbnail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrencyTest {
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
    fun anUrgencyRisesWithTheOnesItFollowsAndNeverFalls() {
        val flight = Urgency.following(Urgency(ThumbPriority.Nearby))
        val lent = Urgency.following(flight)
        assertEquals(ThumbPriority.Nearby.ordinal, lent.rank)
        flight.follow(Urgency(ThumbPriority.Visible))
        assertEquals(ThumbPriority.Visible.ordinal, lent.rank)
        flight.follow(Urgency(ThumbPriority.Nearby))
        assertEquals(ThumbPriority.Visible.ordinal, lent.rank)
    }
}
