package io.github.yuubiz.hirari.core.image.fuzz

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InputGuardTest {
    private val limit = 100L

    private fun input(name: String, startedAt: Long) = RunningInput(startedAt, "input=$name\n", name.toByteArray())

    @Test
    fun anInputIsOverdueOnlyAfterTheLimit() {
        val guard = InputGuard()
        assertTrue(guard.begin(input("a", 0), {}))
        assertNull(guard.claim(100, limit))
        assertEquals("input=a\n", guard.claim(101, limit)?.description)
    }

    @Test
    fun nothingIsOverdueBetweenInputs() {
        val guard = InputGuard()
        assertNull(guard.claim(1_000, limit))
        guard.begin(input("a", 0), {})
        assertTrue(guard.end())
        assertNull(guard.claim(1_000, limit))
    }

    @Test
    fun theStartOfAnEarlierInputDoesNotMakeTheNextOneOverdue() {
        val guard = InputGuard()
        guard.begin(input("a", 0), {})
        guard.end()
        guard.begin(input("b", 400), {})
        assertNull(guard.claim(450, limit))
        assertEquals("input=b\n", guard.claim(501, limit)?.description)
    }

    @Test
    fun theRunnerCannotAdvanceOnceAnInputIsClaimed() {
        val guard = InputGuard()
        guard.begin(input("a", 0), {})
        val claimed = guard.claim(500, limit)
        assertEquals("input=a\n", claimed?.description)
        val saved = mutableListOf<String>()
        assertFalse(guard.begin(input("b", 501)) { saved += it.description })
        assertFalse(guard.end())
        assertTrue(saved.isEmpty())
        assertNull(guard.claim(10_000, limit))
    }

    @Test
    fun aClaimedInputKeepsItsOwnBytes() {
        val guard = InputGuard()
        val a = input("a", 0)
        guard.begin(a, {})
        assertSame(a, guard.claim(500, limit))
        assertArrayEquals("a".toByteArray(), a.bytes)
    }

    @Test
    fun theSaveRunsBeforeTheInputCanBeClaimed() {
        val guard = InputGuard()
        val events = mutableListOf<String>()
        guard.begin(input("a", 0)) { events += "saved ${it.description.trim()}" }
        events += "claimed ${guard.claim(500, limit)?.description?.trim()}"
        assertEquals(listOf("saved input=a", "claimed input=a"), events)
    }

    @Test
    fun aWatchdogThatRacesTheRunnerSavesEitherTheInputItJudgedOrNone() {
        repeat(200) {
            val guard = InputGuard()
            val judged = java.util.concurrent.atomic.AtomicReference<RunningInput?>()
            val saved = mutableListOf<String>()
            val runner = Thread {
                for (n in 0 until 50) {
                    if (!guard.begin(input("n$n", n.toLong() * 1_000)) { synchronized(saved) { saved += it.description } }) return@Thread
                    if (!guard.end()) return@Thread
                }
            }
            val watchdog = Thread { judged.set(guard.claim(50_000, limit)) }
            runner.start()
            watchdog.start()
            runner.join()
            watchdog.join()
            val claimed = judged.get()
            if (claimed != null) {
                synchronized(saved) { assertEquals(claimed.description, saved.last()) }
                assertFalse(guard.begin(input("later", 60_000), {}))
            }
        }
    }
}
