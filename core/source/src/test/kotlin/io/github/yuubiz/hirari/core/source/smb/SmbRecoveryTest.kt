package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * What a broken or stalled connection costs, against the Samba fixture (docs/spec.md section 6.5). A [Relay]
 * between the client and Samba goes silent or slow; `docker pause` stops the server with its sockets open. The
 * printed times are the evidence for the timeouts in the spec.
 */
class SmbRecoveryTest {
    private val scope = CoroutineScope(SupervisorJob())
    private var relay: Relay? = null
    private var paused = false

    @After
    fun tearDown() {
        if (paused) SmbFixture.docker("unpause")
        relay?.close()
        scope.cancel()
    }

    private fun relayed(): Pair<Relay, SmbServer> {
        SmbFixture.require()
        val relay = Relay(SmbFixture.host!!, SmbFixture.port).also { relay = it }
        return relay to SmbServer(SmbAddress("127.0.0.1", "books", relay.port), SmbFixture.login(), scope)
    }

    private fun SmbByteSource.check(offset: Long, length: Int = 4096) {
        val out = ByteBuffer.allocate(length)
        while (out.hasRemaining() && read(offset + out.position(), out) > 0) Unit
        assertArrayEquals("bytes at $offset", SmbFixture.pattern.copyOfRange(offset.toInt(), offset.toInt() + length), out.array())
    }

    private fun SmbServer.awaitState(state: SmbConnectionState, seconds: Long) =
        runBlocking { withTimeout(seconds * 1000) { this@awaitState.state.first { it == state } } }

    private val requestTimeout = SmbTimeouts().request.inWholeMilliseconds

    @Test
    fun `a connection that goes silent fails the read after the request timeout and is replaced`() {
        val (relay, server) = relayed()
        server.use {
            val source = server.open(SmbFixture.BIG)
            source.check(0)
            relay.silenceOpenConnections()
            val (millis, result) = timed("read on a silent connection") { source.check(4L * FAR_OFFSET) }
            assertTrue("fails as a lost connection: ${result.exceptionOrNull()}", result.exceptionOrNull() is ConnectionLostException)
            assertTrue("fails after the request timeout: $millis ms", millis in requestTimeout - 100..requestTimeout + 3000)
            val (reconnect, _) = timed("reconnect after the silent connection") { server.awaitState(SmbConnectionState.Connected, 10) }
            assertTrue("the first attempt after 0.5 s connects: $reconnect ms", reconnect < 3000)
            source.check(4L * FAR_OFFSET)
            assertEquals("a new TCP connection", 2, relay.accepted.get())
        }
    }

    @Test
    fun `a slow server that answers within the timeout keeps its connection`() {
        val (relay, server) = relayed()
        server.use {
            val source = server.open(SmbFixture.BIG)
            source.check(0)
            relay.answerDelayMillis = 3000
            val (millis, result) = timed("read with every answer 3 s late") { source.check(6L * FAR_OFFSET) }
            relay.answerDelayMillis = 0
            assertTrue("the slow read succeeds: ${result.exceptionOrNull()}", result.isSuccess)
            assertTrue("it waited for the late answers: $millis ms", millis >= 3000)
            assertEquals(SmbConnectionState.Connected, server.state.value)
            assertEquals(1, relay.accepted.get())
        }
    }

    @Test
    fun `an idle connection is kept and answers at once`() {
        val (relay, server) = relayed()
        server.use {
            val source = server.open(SmbFixture.BIG)
            source.check(0)
            Thread.sleep(20_000)
            val (millis, result) = timed("read after 20 s idle") { source.check(5L * FAR_OFFSET) }
            assertTrue(result.isSuccess)
            assertTrue("no wait: $millis ms", millis < 2000)
            assertEquals(1, relay.accepted.get())
        }
    }

    @Test
    fun `a paused server fails the read, and the server reconnects once it answers again`() {
        SmbFixture.requireContainer()
        SmbServer(SmbAddress(SmbFixture.host!!, "books", SmbFixture.port), SmbFixture.login(), scope).use { server ->
            val source = server.open(SmbFixture.BIG)
            source.check(0)
            SmbFixture.docker("pause")
            paused = true
            val (millis, result) = timed("read while the server is paused") { source.check(7L * FAR_OFFSET) }
            assertTrue("fails as a lost connection: ${result.exceptionOrNull()}", result.exceptionOrNull() is ConnectionLostException)
            assertTrue("fails after the request timeout: $millis ms", millis in requestTimeout - 100..requestTimeout + 3000)
            val (attempt, _) = timed("first reconnect attempt while paused") { server.awaitState(SmbConnectionState.Reconnecting(2), 15) }
            assertTrue("a paused server fails the attempt after the connect timeout: $attempt ms", attempt < 8000)
            SmbFixture.docker("unpause")
            paused = false
            val (back, _) = timed("reconnect after unpause") { server.awaitState(SmbConnectionState.Connected, 20) }
            assertTrue("reconnects within the remaining attempts: $back ms", back < 20_000)
            source.check(7L * FAR_OFFSET)
        }
    }
}
