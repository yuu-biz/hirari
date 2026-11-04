package io.github.yuubiz.hirari.core.source.smb

import io.github.yuubiz.hirari.core.source.smb.SmbPurpose.Background
import io.github.yuubiz.hirari.core.source.smb.SmbPurpose.Foreground
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** The request permits of docs/spec.md section 8.3, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestGateTest {
    private val queueTimeout = 15.seconds
    private val gate = RequestGate(MAX_REQUESTS_PER_SERVER, MAX_BACKGROUND_REQUESTS_PER_SERVER)

    private fun TestScope.acquire(purpose: SmbPurpose, started: MutableList<Long>? = null) = async {
        gate.acquire(purpose, queueTimeout).also { started?.add(testScheduler.currentTime) }
    }

    @Test
    fun `a listing gets through at once while four cover requests hold or wait`() = runTest {
        val covers = List(4) { acquire(Background) }
        runCurrent()
        assertEquals("three covers hold permits, the fourth waits", 3, covers.count { it.isCompleted })

        val listing = acquire(Foreground)
        runCurrent()
        assertTrue(listing.isCompleted)
        assertTrue(listing.await())
        assertEquals("no virtual time passed", 0L, testScheduler.currentTime)
        assertFalse("the fourth cover still waits", covers.all { it.isCompleted })
    }

    @Test
    fun `a listing is not held up for the queue timeout by a long running cover load`() = runTest {
        val held = List(3) { acquire(Background) }
        val waiting = List(5) { acquire(Background) }
        runCurrent()
        assertTrue(held.all { it.isCompleted })

        val times = mutableListOf<Long>()
        repeat(6) {
            val listing = acquire(Foreground, times)
            runCurrent()
            assertTrue("listing $it got its permit", listing.isCompleted && listing.await())
            testScheduler.advanceTimeBy(2.seconds)
            gate.release(Foreground)
        }
        assertEquals("each listing started when it asked, not after covers freed permits", listOf(0L, 2000L, 4000L, 6000L, 8000L, 10000L), times)
        assertTrue("the covers held on for the whole time", waiting.none { it.isCompleted })

        testScheduler.advanceTimeBy(queueTimeout - 12.seconds)
        runCurrent()
        assertEquals("the waiting covers gave up after the queue timeout", List(5) { false }, waiting.map { it.await() })
        assertEquals(queueTimeout.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun `a freed permit goes to a waiting listing before a cover that has waited longer`() = runTest {
        val held = List(3) { acquire(Background) }
        val listing = acquire(Foreground)
        runCurrent()
        val reader = acquire(Foreground)
        val nextCover = acquire(Background)
        runCurrent()
        assertTrue(listing.isCompleted)
        assertFalse(reader.isCompleted)

        gate.release(Foreground)
        runCurrent()
        assertTrue("the reader took the permit the listing freed", reader.isCompleted && reader.await())
        assertFalse(nextCover.isCompleted)

        gate.release(Background)
        runCurrent()
        assertTrue("a freed cover permit lets the waiting cover in", nextCover.isCompleted && nextCover.await())
        held.forEach { assertTrue(it.await()) }
    }

    @Test
    fun `a cover waits for a permit that foreground requests hold, up to the queue timeout`() = runTest {
        val foreground = List(4) { acquire(Foreground) }
        val cover = acquire(Background)
        runCurrent()
        assertTrue(foreground.all { it.isCompleted })
        assertFalse(cover.isCompleted)

        testScheduler.advanceTimeBy(queueTimeout - 1.seconds)
        runCurrent()
        assertFalse(cover.isCompleted)
        gate.release(Foreground)
        runCurrent()
        assertTrue("it got the permit a second before it gave up", cover.await())
    }

    @Test
    fun `a request that waited its queue timeout fails and leaves no trace`() = runTest {
        val held = List(3) { acquire(Background) }
        val timedOut = acquire(Background)
        runCurrent()
        testScheduler.advanceTimeBy(queueTimeout)
        runCurrent()
        assertFalse(timedOut.await())

        gate.release(Background)
        val next = acquire(Background)
        runCurrent()
        assertTrue("the permit the timed out request never held is free", next.await())
        held.forEach { assertTrue(it.await()) }
    }

    @Test
    fun `cancelling a waiting request frees its place and cancelling one that was just granted frees its permit`() = runTest {
        List(3) { acquire(Background) }
        val waiting = acquire(Background)
        runCurrent()
        waiting.cancel()
        runCurrent()
        gate.release(Background)
        val next = acquire(Background)
        runCurrent()
        assertTrue("the cancelled request took no permit", next.await())

        val foreground = acquire(Foreground)
        runCurrent()
        assertTrue(foreground.await())
        val waitingForeground = launch { gate.acquire(Foreground, queueTimeout) }
        runCurrent()
        gate.release(Foreground)
        waitingForeground.cancel()
        runCurrent()
        val after = acquire(Foreground)
        runCurrent()
        assertTrue("the permit granted to the cancelled request came back", after.isCompleted && after.await())
    }
}
