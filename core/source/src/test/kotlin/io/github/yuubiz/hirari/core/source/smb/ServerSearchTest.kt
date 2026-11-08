package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSearchTest {
    private val home = LanLink(Ipv4Network(ip("192.168.1.20"), 24)) { DatagramSocket(null) }
    private val dnsSd = MutableSharedFlow<DnsSdService>(extraBufferCapacity = 16)
    private val netBios = MutableSharedFlow<NetBiosHost>(extraBufferCapacity = 16)
    private val sentTo = mutableListOf<List<NetBiosTarget>>()

    private fun TestScope.finder(links: List<LanLink> = listOf(home)) = ServerFinder(
        lan = { links },
        dnsSd = { dnsSd },
        netBios = { targets -> sentTo += targets; netBios },
        io = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.collect(finder: ServerFinder): MutableList<ServerSearch> {
        val seen = mutableListOf<ServerSearch>()
        backgroundScope.launch { finder.search().toList(seen) }
        runCurrent()
        return seen
    }

    @Test
    fun `without an IPv4 network nothing is searched`() = runTest {
        var asked = false
        val finder = ServerFinder({ emptyList() }, { asked = true; emptyFlow() }, { asked = true; emptyFlow() }, io = StandardTestDispatcher(testScheduler))
        assertEquals(listOf<ServerSearch>(ServerSearch.NoNetwork), finder.search().toList())
        assertFalse(asked)
    }

    @Test
    fun `NetBIOS goes to the broadcast address of each network that has one`() = runTest {
        val point = LanLink(Ipv4Network(ip("10.9.0.1"), 32)) { DatagramSocket(null) }
        val seen = collect(finder(listOf(home, point)))
        val target = sentTo.single().single()
        assertEquals(ip("192.168.1.255"), target.destination.address)
        assertEquals(137, target.destination.port)
        assertEquals(home.network, target.network)
        assertEquals(ServerSearch.Searching(emptyList()), seen.last())
    }

    @Test
    fun `one row per address, named by DNS-SD before NetBIOS, with the DNS-SD port`() = runTest {
        val seen = collect(finder())
        netBios.tryEmit(NetBiosHost("NAS", ip("192.168.1.5")))
        runCurrent()
        assertEquals(DiscoveredServer("NAS", ip("192.168.1.5"), 445, setOf(DiscoverySource.NetBios), "NAS"), found(seen).single())
        dnsSd.tryEmit(DnsSdService("My NAS", host = null, ip("192.168.1.5"), 4450))
        runCurrent()
        netBios.tryEmit(NetBiosHost("NAS", ip("192.168.1.5")))
        runCurrent()
        assertEquals(
            DiscoveredServer("My NAS", ip("192.168.1.5"), 4450, setOf(DiscoverySource.NetBios, DiscoverySource.DnsSd), "NAS"),
            found(seen).single(),
        )
    }

    @Test
    fun `a DNS-SD host name is tried before the NetBIOS name, and a later NetBIOS answer does not rename the row`() = runTest {
        val seen = collect(finder())
        dnsSd.tryEmit(DnsSdService("Mac", host = "mac.local", ip("192.168.1.7"), 445))
        runCurrent()
        netBios.tryEmit(NetBiosHost("MACBOOK", ip("192.168.1.7")))
        runCurrent()
        assertEquals(DiscoveredServer("Mac", ip("192.168.1.7"), 445, setOf(DiscoverySource.DnsSd, DiscoverySource.NetBios), "mac.local"), found(seen).single())
    }

    @Test
    fun `an unnamed NetBIOS answer is named by its address and has no name to try`() = runTest {
        val seen = collect(finder())
        netBios.tryEmit(NetBiosHost(null, ip("192.168.1.9")))
        runCurrent()
        assertEquals(DiscoveredServer("192.168.1.9", ip("192.168.1.9"), 445, setOf(DiscoverySource.NetBios), null), found(seen).single())
    }

    @Test
    fun `rows are in natural order as they come`() = runTest {
        val seen = collect(finder())
        netBios.tryEmit(NetBiosHost("nas10", ip("192.168.1.10")))
        runCurrent()
        netBios.tryEmit(NetBiosHost("nas9", ip("192.168.1.9")))
        runCurrent()
        dnsSd.tryEmit(DnsSdService("archive", null, ip("192.168.1.30"), 445))
        runCurrent()
        assertEquals(listOf("archive", "nas9", "nas10"), found(seen).map { it.name })
    }

    @Test
    fun `the search settles after 6 s and DNS-SD goes on adding rows`() = runTest {
        val seen = collect(finder())
        netBios.tryEmit(NetBiosHost("NAS", ip("192.168.1.5")))
        runCurrent()
        advanceTimeBy(5_999.milliseconds)
        runCurrent()
        assertIs<ServerSearch.Searching>(seen.last())
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(ServerSearch.Settled(found(seen)), seen.last())
        dnsSd.tryEmit(DnsSdService("Late", null, ip("192.168.1.8"), 445))
        runCurrent()
        val last = assertIs<ServerSearch.Settled>(seen.last())
        assertEquals(listOf("Late", "NAS"), last.found.map { it.name })
    }

    @Test
    fun `a source that fails adds nothing and leaves the other searching`() = runTest {
        val finder = ServerFinder(
            lan = { listOf(home) },
            dnsSd = { flow { throw IllegalStateException("discovery did not start") } },
            netBios = { netBios },
            io = StandardTestDispatcher(testScheduler),
        )
        val seen = collect(finder)
        netBios.tryEmit(NetBiosHost("NAS", ip("192.168.1.5")))
        runCurrent()
        assertEquals(listOf("NAS"), found(seen).map { it.name })
    }

    @Test
    fun `the host field gets the name only when it looks up to the same address`() = runTest {
        val server = DiscoveredServer("My NAS", ip("192.168.1.5"), 445, setOf(DiscoverySource.NetBios), "nas")
        val inline = java.util.concurrent.Executor { it.run() }
        assertEquals("nas", server.hostField(lookup = { listOf(ip("10.0.0.1"), ip("192.168.1.5")) }, executor = inline))
        assertEquals("192.168.1.5", server.hostField(lookup = { listOf(ip("192.168.1.6")) }, executor = inline))
        assertEquals("192.168.1.5", server.hostField(lookup = { throw java.net.UnknownHostException(it) }, executor = inline))
        var looked = false
        assertEquals("192.168.1.5", server.copy(hostName = null).hostField(lookup = { looked = true; emptyList() }, executor = inline))
        assertFalse(looked)
    }

    @Test
    fun `a lookup that does not answer in time gives the address and is left behind`() = runBlocking {
        val server = DiscoveredServer("NAS", ip("192.168.1.5"), 445, setOf(DiscoverySource.NetBios), "nas")
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val started = System.nanoTime()
            val host = withTimeout(2.seconds) {
                server.hostField(limit = 100.milliseconds, lookup = { release.await(); listOf(ip("192.168.1.5")) }, executor = pool)
            }
            assertEquals("192.168.1.5", host)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
            val cancelled = CompletableDeferred<String>()
            val job = launch { cancelled.complete(server.hostField(lookup = { release.await(); emptyList() }, executor = pool)) }
            job.cancel()
            withTimeout(1.seconds) { job.join() }
            assertFalse(cancelled.isCompleted)
        } finally {
            release.countDown()
            pool.shutdown()
        }
    }

    private fun found(seen: List<ServerSearch>): List<DiscoveredServer> = when (val last = seen.last()) {
        is ServerSearch.Searching -> last.found
        is ServerSearch.Settled -> last.found
        else -> error("no rows in $last")
    }
}

private fun ip(dotted: String): Inet4Address = InetAddress.getByName(dotted) as Inet4Address
