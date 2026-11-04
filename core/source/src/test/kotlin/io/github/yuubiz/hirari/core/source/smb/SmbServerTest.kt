package io.github.yuubiz.hirari.core.source.smb

import io.github.yuubiz.hirari.core.source.smb.SmbConnectionState.Connected
import io.github.yuubiz.hirari.core.source.smb.SmbConnectionState.Lost
import io.github.yuubiz.hirari.core.source.smb.SmbConnectionState.Reconnecting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/** The reconnect policy of docs/spec.md section 6.5 over an in-memory share, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SmbServerTest {
    private val bytes = ByteArray(3 * FAR_OFFSET + 100) { (it * 7 + it / 251).toByte() }
    private val connector = FakeConnector(mapOf("book.zip" to bytes))

    private fun TestScope.server(networkAvailable: Flow<Unit> = emptyFlow()) =
        SmbServer(connector, SmbLogin.Guest, backgroundScope, networkAvailable, StandardTestDispatcher(testScheduler), 1.seconds)

    /** The states [server] goes through, with the virtual time of each. */
    private fun TestScope.timeline(server: SmbServer): List<Pair<Long, SmbConnectionState>> {
        val seen = mutableListOf<Pair<Long, SmbConnectionState>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            server.state.collect { seen += testScheduler.currentTime to it }
        }
        return seen
    }

    /** Runs the server's coroutines, which live in the background scope that advanceUntilIdle leaves alone. */
    private fun TestScope.settle() {
        testScheduler.advanceTimeBy(60_000)
        runCurrent()
    }

    private fun SmbByteSource.readAt(offset: Long, length: Int): ByteArray {
        val out = ByteBuffer.allocate(length)
        while (out.hasRemaining() && read(offset + out.position(), out) > 0) Unit
        return out.array().copyOf(out.position())
    }

    @Test
    fun `a broken connection fails the read and reconnects after half a second`() = runTest {
        val server = server()
        val source = server.open("book.zip")
        assertArrayEquals(bytes.copyOf(100), source.readAt(0, 100))
        connector.current.breakConnection()
        assertThrows(ConnectionLostException::class.java) { source.readAt(FAR_OFFSET.toLong(), 100) }
        assertEquals(Reconnecting(1), server.state.value)

        testScheduler.advanceTimeBy(499)
        runCurrent()
        assertEquals("no attempt before 0.5 s", 1, connector.attempts.get())
        testScheduler.advanceTimeBy(1)
        runCurrent()
        assertEquals(Connected, server.state.value)
        assertArrayEquals(
            "the source reopens its file on the new connection",
            bytes.copyOfRange(FAR_OFFSET, FAR_OFFSET + 100),
            source.readAt(FAR_OFFSET.toLong(), 100),
        )
        assertEquals(1, connector.current.opens.get())
    }

    @Test
    fun `a connection that ended while unused is made again by the next request without waiting`() = runTest {
        val server = server()
        val seen = timeline(server)
        val source = server.open("book.zip")
        assertArrayEquals(bytes.copyOf(100), source.readAt(0, 100))
        connector.current.end()
        assertEquals(emptyList<SmbEntry>(), server.list("folder"))
        assertArrayEquals(bytes.copyOfRange(FAR_OFFSET, FAR_OFFSET + 100), source.readAt(FAR_OFFSET.toLong(), 100))
        connector.current.end()
        server.open("book.zip").use { assertArrayEquals(bytes.copyOf(10), it.readAt(0, 10)) }
        settle()
        assertEquals("the first connect and one for each ended connection", 3, connector.attempts.get())
        assertEquals(listOf(0L to SmbConnectionState.Idle, 0L to Connected), seen)
    }

    @Test
    fun `a connection that ended and cannot be made again fails the request and starts the reconnects`() = runTest {
        val server = server()
        val seen = timeline(server)
        val source = server.open("book.zip")
        connector.failures = 10
        connector.current.end()
        assertThrows(ConnectionLostException::class.java) { source.readAt(0, 10) }
        assertThrows(ConnectionLostException::class.java) { server.open("book.zip") }
        settle()
        assertEquals(
            listOf(
                0L to SmbConnectionState.Idle,
                0L to Connected,
                0L to Reconnecting(1),
                500L to Reconnecting(2),
                1500L to Reconnecting(3),
                3500L to Reconnecting(4),
                7500L to Lost,
            ),
            seen,
        )
        assertEquals("the first connect, the one in the request, and four attempts", 6, connector.attempts.get())
    }

    @Test
    fun `closing while a request connects again drops the new connection`() {
        val connecting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val made = mutableListOf<FakeShare>()
        val calls = AtomicInteger()
        val connector = ShareConnector {
            if (calls.incrementAndGet() == 2) {
                connecting.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            FakeShare(mapOf("book.zip" to bytes)).also { synchronized(made) { made += it } }
        }
        val scope = CoroutineScope(Job())
        val server = SmbServer(connector, SmbLogin.Guest, scope, emptyFlow(), kotlinx.coroutines.Dispatchers.IO, 10.seconds)
        server.open("book.zip").close()
        made.single().end()
        val pool = Executors.newSingleThreadExecutor()
        val request = pool.submit<Throwable?> { runCatching { server.open("book.zip") }.exceptionOrNull() }
        assertTrue(connecting.await(10, TimeUnit.SECONDS))
        server.close()
        release.countDown()
        assertTrue(request.get(10, TimeUnit.SECONDS) is ConnectionLostException)
        assertTrue("the connection made after the close is dropped", made.last().broken)
        assertEquals(Lost, server.state.value)
        pool.shutdown()
        scope.cancel()
    }

    @Test
    fun `four failed attempts at 0,5, 1, 2 and 4 s leave the connection lost`() = runTest {
        val server = server()
        val seen = timeline(server)
        val source = server.open("book.zip")
        connector.failures = 10
        connector.current.breakConnection()
        assertThrows(ConnectionLostException::class.java) { source.readAt(0, 10) }
        settle()
        assertEquals(
            listOf(
                0L to SmbConnectionState.Idle,
                0L to Connected,
                0L to Reconnecting(1),
                500L to Reconnecting(2),
                1500L to Reconnecting(3),
                3500L to Reconnecting(4),
                7500L to Lost,
            ),
            seen,
        )
        assertEquals("the first connect and four attempts", 5, connector.attempts.get())
    }

    @Test
    fun `a lost connection waits for the network and then starts the attempts over`() = runTest {
        val network = MutableSharedFlow<Unit>()
        val server = server(network)
        val source = server.open("book.zip")
        connector.failures = 4
        connector.current.breakConnection()
        assertThrows(ConnectionLostException::class.java) { source.readAt(0, 10) }
        settle()
        assertEquals(Lost, server.state.value)
        assertEquals(5, connector.attempts.get())

        network.emit(Unit)
        runCurrent()
        assertEquals(Reconnecting(1), server.state.value)
        testScheduler.advanceTimeBy(501)
        runCurrent()
        assertEquals(Connected, server.state.value)
        assertArrayEquals(bytes.copyOf(10), source.readAt(0, 10))
    }

    @Test
    fun `the user's retry starts the attempts over only when the connection is lost`() = runTest {
        val server = server()
        server.open("book.zip").use { it.readAt(0, 1) }
        server.retry()
        runCurrent()
        assertEquals("retry does nothing while connected", Connected, server.state.value)

        connector.failures = 4
        connector.current.breakConnection()
        assertThrows(ConnectionLostException::class.java) { server.open("book.zip") }
        settle()
        assertEquals(Lost, server.state.value)
        server.retry()
        settle()
        assertEquals(Connected, server.state.value)
    }

    /** Requests block real threads; only the reconnect schedule advances in virtual time. */
    private fun TestScope.recoverWithRequests(vararg requests: (SmbServer) -> Unit) {
        val server = SmbServer(connector, SmbLogin.Guest, backgroundScope, emptyFlow(), Dispatchers.Unconfined, 10.seconds)
        val source = server.open("book.zip")
        connector.failures = 4
        connector.current.breakConnection()
        assertThrows(ConnectionLostException::class.java) { source.readAt(0, 10) }
        settle()
        assertEquals(Lost, server.state.value)
        assertEquals(5, connector.attempts.get())

        val workers = mutableListOf<Thread>()
        val pool = Executors.newFixedThreadPool(requests.size) { task ->
            thread(start = false, isDaemon = true) { task.run() }.also { workers += it }
        }
        try {
            val results = requests.map { request -> pool.submit { request(server) } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline &&
                (server.state.value != Reconnecting(1) || workers.any {
                    it.state != Thread.State.WAITING && it.state != Thread.State.TIMED_WAITING
                }) && results.none { it.isDone }
            ) Thread.yield()
            assertTrue("all requests are waiting", workers.all {
                it.state == Thread.State.WAITING || it.state == Thread.State.TIMED_WAITING
            })
            assertEquals("the requests start one new schedule", Reconnecting(1), server.state.value)
            results.forEach { assertFalse("the request waits for reconnect", it.isDone) }
            runCurrent()
            testScheduler.advanceTimeBy(499)
            runCurrent()
            assertEquals("no connect before the scheduled delay", 5, connector.attempts.get())
            testScheduler.advanceTimeBy(1)
            runCurrent()
            results.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(Connected, server.state.value)
            assertEquals("all requests share one reconnect", 6, connector.attempts.get())
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a list request after Lost reconnects and succeeds`() = runTest {
        recoverWithRequests({ server ->
            assertEquals(emptyList<SmbEntry>(), kotlinx.coroutines.runBlocking { server.list("folder") })
        })
    }

    @Test
    fun `a background request after Lost reconnects and succeeds`() = runTest {
        recoverWithRequests({ server ->
            server.open("book.zip", SmbPurpose.Background).use { assertArrayEquals(bytes.copyOf(10), it.readAt(0, 10)) }
        })
    }

    @Test
    fun `two requests after Lost wait for one reconnect`() = runTest {
        recoverWithRequests(
            { server -> server.open("book.zip").use { assertArrayEquals(bytes.copyOf(10), it.readAt(0, 10)) } },
            { server -> server.open("book.zip", SmbPurpose.Background).use { assertArrayEquals(bytes.copyOf(10), it.readAt(0, 10)) } },
        )
    }

    @Test
    fun `a closed server fails foreground and background requests at once`() = runTest {
        val server = server()
        server.close()
        val before = testScheduler.currentTime
        assertThrows(ConnectionLostException::class.java) { server.open("book.zip") }
        assertThrows(ConnectionLostException::class.java) { server.open("book.zip", SmbPurpose.Background) }
        assertEquals(before, testScheduler.currentTime)
        settle()
        assertEquals(Lost, server.state.value)
        assertEquals(0, connector.attempts.get())
    }

    @Test
    fun `requests while reconnecting fail at once without connecting`() = runTest {
        val server = server()
        val source = server.open("book.zip")
        connector.current.breakConnection()
        assertThrows(ConnectionLostException::class.java) { source.readAt(0, 10) }
        val attempts = connector.attempts.get()
        assertThrows(ConnectionLostException::class.java) { server.open("book.zip") }
        assertThrows(ConnectionLostException::class.java) { source.readAt(FAR_OFFSET * 2L, 10) }
        assertEquals(attempts, connector.attempts.get())
    }

    @Test
    fun `sources failing on the same connection start one reconnect`() = runTest {
        val server = server()
        val sources = List(3) { server.open("book.zip") }
        connector.current.breakConnection()
        sources.forEach { source -> assertThrows(ConnectionLostException::class.java) { source.readAt(0, 10) } }
        settle()
        assertEquals("one first connect, one reconnect", 2, connector.attempts.get())
        assertEquals(Connected, server.state.value)
    }

    @Test
    fun `a missing file leaves the connection alone`() = runTest {
        val server = server()
        assertThrows(FileNotFoundException::class.java) { server.open("missing.zip") }
        assertEquals(Connected, server.state.value)
        assertEquals(1, connector.attempts.get())
    }

    @Test
    fun `a first connect that fails reports why and stays idle`() = runTest {
        connector.failures = 1
        val server = server()
        assertEquals(SmbConnectResult.Failed(SmbConnectFailure.Unreachable), server.check())
        assertEquals(SmbConnectionState.Idle, server.state.value)
        assertEquals(SmbConnectResult.Connected, server.check())
    }

    @Test
    fun `no more than four requests run at once`() {
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val release = CountDownLatch(1)
        val full = CountDownLatch(MAX_REQUESTS_PER_SERVER)
        val connector = FakeConnector(mapOf("book.zip" to bytes)) {
            most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            full.countDown()
            release.await(10, TimeUnit.SECONDS)
            inFlight.decrementAndGet()
        }
        val scope = CoroutineScope(Job())
        val server = SmbServer(connector, SmbLogin.Guest, scope, emptyFlow(), kotlinx.coroutines.Dispatchers.IO, 10.seconds)
        val pool = Executors.newFixedThreadPool(8)
        val reads = List(8) { pool.submit { server.open("book.zip").use { it.readAt(0, 10) } } }
        assertTrue("four reads are in flight", full.await(10, TimeUnit.SECONDS))
        Thread.sleep(200)
        release.countDown()
        reads.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        scope.cancel()
        assertEquals(MAX_REQUESTS_PER_SERVER, most.get())
    }

    @Test
    fun `a closed server is lost to an open source and never reconnects`() = runTest {
        val networkAvailable = MutableSharedFlow<Unit>()
        val server = server(networkAvailable)
        val source = server.open("book.zip")
        assertArrayEquals(bytes.copyOf(100), source.readAt(0, 100))
        server.close()
        assertEquals(Lost, server.state.value)
        assertThrows(ConnectionLostException::class.java) { source.readAt(FAR_OFFSET.toLong(), 100) }
        assertThrows(ConnectionLostException::class.java) { server.open("book.zip") }

        server.retry()
        networkAvailable.emit(Unit)
        settle()
        assertEquals(Lost, server.state.value)
        assertEquals(1, connector.attempts.get())
    }

    @Test
    fun `closing forgets the password and the login never prints it`() = runTest {
        val password = "s3cret-pass".toCharArray()
        val login = SmbLogin.Password("someone", password)
        password.fill('x')
        val server = SmbServer(connector, login, backgroundScope, emptyFlow(), StandardTestDispatcher(testScheduler), 1.seconds)
        assertEquals("s3cret-pass", String(login.password))
        assertTrue(login.toString(), "someone" !in login.toString() && "s3cret" !in login.toString())
        server.close()
        assertTrue(login.password.all { it == '\u0000' })
    }
}
