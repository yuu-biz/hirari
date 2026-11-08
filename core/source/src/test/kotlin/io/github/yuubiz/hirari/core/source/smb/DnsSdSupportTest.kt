package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class CountingHold : MulticastHold {
    var acquired = 0
    var released = 0

    override fun acquire() {
        acquired++
    }

    override fun release() {
        released++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MulticastHoldTest {
    private val hold = CountingHold()

    @Test
    fun `the hold is taken when collection starts and given back when it is cancelled`() = runTest {
        val flow = flow<Int> { awaitCancellation() }.whileHolding(hold)
        assertEquals(0, hold.acquired)
        val search = backgroundScope.launch { flow.toList() }
        runCurrent()
        assertEquals(1, hold.acquired)
        assertEquals(0, hold.released)
        search.cancel()
        runCurrent()
        assertEquals(1, hold.released)
    }

    @Test
    fun `the hold is given back when the flow completes or fails`() = runTest {
        assertEquals(listOf(1, 2), flow { emit(1); emit(2) }.whileHolding(hold).toList())
        assertEquals(1, hold.released)
        flow<Int> { throw IllegalStateException("discovery did not start") }.whileHolding(hold).catch { }.toList()
        assertEquals(2, hold.acquired)
        assertEquals(2, hold.released)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SerialResolverTest {
    private class Started(val key: String, val reply: (Resolution) -> Unit)

    private val started = mutableListOf<Started>()

    private fun kotlinx.coroutines.test.TestScope.resolver(limit: kotlin.time.Duration = 10.seconds, onStart: (String) -> Unit = {}) =
        SerialResolver<String>(limit, backgroundScope) { key, reply ->
            onStart(key)
            started += Started(key, reply)
        }

    private fun service(name: String) = DnsSdService(name, null, InetAddress.getByName("192.168.1.5") as Inet4Address, 445)

    @Test
    fun `a resolve left running by a cancelled search finishes before the next search resolves`() = runTest {
        val resolver = resolver()
        val first = launch { resolver.resolve("a") }
        runCurrent()
        assertEquals(listOf("a"), started.map { it.key })
        first.cancel()
        runCurrent()
        val second = async { resolver.resolve("b") }
        runCurrent()
        assertEquals(listOf("a"), started.map { it.key }, "b waits for the system to answer a")
        started[0].reply(Resolution.Found(service("a")))
        runCurrent()
        assertEquals(listOf("a", "b"), started.map { it.key })
        started[1].reply(Resolution.Found(service("b")))
        runCurrent()
        assertEquals("b", second.await()?.name)
    }

    @Test
    fun `a busy answer is tried again later and is a failure after the retries`() = runTest {
        val resolver = resolver()
        val retried = async { resolver.resolve("a") }
        runCurrent()
        started[0].reply(Resolution.Busy)
        runCurrent()
        assertEquals(1, started.size)
        advanceTimeBy(BUSY_RETRY_DELAY)
        runCurrent()
        assertEquals(2, started.size)
        started[1].reply(Resolution.Found(service("a")))
        runCurrent()
        assertEquals("a", retried.await()?.name)

        started.clear()
        val refused = async { resolver.resolve("b") }
        repeat(BUSY_RETRIES + 1) { attempt ->
            advanceTimeBy(BUSY_RETRY_DELAY)
            runCurrent()
            assertEquals(attempt + 1, started.size)
            started[attempt].reply(Resolution.Busy)
            runCurrent()
        }
        advanceTimeBy(BUSY_RETRY_DELAY * 2)
        runCurrent()
        assertNull(refused.await())
        assertEquals(BUSY_RETRIES + 1, started.size)
    }

    @Test
    fun `a failed resolve gives nothing and frees the slot`() = runTest {
        val resolver = resolver()
        val failed = async { resolver.resolve("a") }
        runCurrent()
        started[0].reply(Resolution.Failed)
        runCurrent()
        assertNull(failed.await())
        val next = async { resolver.resolve("b") }
        runCurrent()
        assertEquals(listOf("a", "b"), started.map { it.key })
        started[1].reply(Resolution.Found(service("b")))
        runCurrent()
        assertEquals("b", next.await()?.name)
    }

    @Test
    fun `a resolve the system never answers frees the slot after the limit`() = runTest {
        val resolver = resolver(limit = 3.seconds)
        val lost = launch { resolver.resolve("a") }
        runCurrent()
        lost.cancel()
        val next = async { resolver.resolve("b") }
        advanceTimeBy(2_999.milliseconds)
        runCurrent()
        assertEquals(listOf("a"), started.map { it.key })
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(listOf("a", "b"), started.map { it.key })
        started[1].reply(Resolution.Found(service("b")))
        runCurrent()
        assertEquals("b", next.await()?.name)
    }

    @Test
    fun `a resolve that cannot start gives nothing and frees the slot`() = runTest {
        var broken = true
        val resolver = resolver(onStart = { if (broken) throw IllegalArgumentException("listener in use") })
        assertNull(resolver.resolve("a"))
        broken = false
        val next = async { resolver.resolve("b") }
        runCurrent()
        started.last().reply(Resolution.Found(service("b")))
        runCurrent()
        assertEquals("b", next.await()?.name)
    }
}
