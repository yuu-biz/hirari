package io.github.yuubiz.hirari.core.source.smb

import io.github.yuubiz.hirari.core.model.NaturalOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class DiscoverySource { DnsSd, NetBios }

/**
 * One row of サーバーを探す (docs/spec.md section 4.5), merged by [address]. [name] is the DNS-SD service name, else
 * the NetBIOS name, else the address. [hostName] is what the host field tries before the address: the DNS-SD host
 * name, else the NetBIOS name.
 */
data class DiscoveredServer(
    val name: String,
    val address: Inet4Address,
    val port: Int,
    val sources: Set<DiscoverySource>,
    val hostName: String?,
)

/** A DNS-SD `_smb._tcp` service resolved to IPv4. [host] is its host name where the system tells it. */
data class DnsSdService(val name: String, val host: String?, val address: Inet4Address, val port: Int)

fun interface DnsSdBrowser {
    /** The services found and resolved, until the collector stops. */
    fun services(): Flow<DnsSdService>
}

/** An IPv4 network the search sends on, with a socket bound to it. */
class LanLink(val network: Ipv4Network, val openSocket: () -> DatagramSocket)

fun interface LanNetworks {
    /** The IPv4 networks over Wi-Fi and Ethernet. Binder calls; call off the main thread. */
    fun links(): List<LanLink>
}

sealed interface ServerSearch {
    /** Within the first [SERVER_SEARCH_SETTLE] of a search. */
    data class Searching(val found: List<DiscoveredServer>) : ServerSearch

    /** After it; DNS-SD goes on adding rows until the collector stops. */
    data class Settled(val found: List<DiscoveredServer>) : ServerSearch

    /** No IPv4 network over Wi-Fi or Ethernet, so nothing was searched. */
    data object NoNetwork : ServerSearch

    /** The caller has no `ACCESS_LOCAL_NETWORK` (docs/spec.md section 7), so nothing was searched. */
    data object NoLocalNetwork : ServerSearch
}

val SERVER_SEARCH_SETTLE = 6.seconds

/**
 * サーバーを探す of docs/spec.md section 4.5: DNS-SD and NetBIOS at once, merged by address, the rows in natural
 * order. A source that fails adds nothing. Collecting runs the search and cancelling ends it.
 */
class ServerFinder(
    private val lan: LanNetworks,
    private val dnsSd: DnsSdBrowser,
    private val netBios: NetBiosBrowser,
    private val settleAfter: Duration = SERVER_SEARCH_SETTLE,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun search(): Flow<ServerSearch> = flow {
        val links = lan.links()
        if (links.isEmpty()) return@flow emit(ServerSearch.NoNetwork)
        val targets = links.mapNotNull { link ->
            link.network.broadcast?.let { NetBiosTarget(InetSocketAddress(it, NETBIOS_NAME_PORT), link.network, link.openSocket) }
        }
        val events = merge(
            dnsSd.services().catch { }.map(SearchEvent::Service),
            netBios.hosts(targets).catch { }.map(SearchEvent::Host),
            flow {
                delay(settleAfter)
                emit(SearchEvent.Settle)
            },
        )
        emitAll(events.scan(SearchProgress()) { progress, event -> progress.after(event) }.map { it.search() }.distinctUntilChanged())
    }.flowOn(io)
}

private sealed interface SearchEvent {
    data class Service(val service: DnsSdService) : SearchEvent

    data class Host(val host: NetBiosHost) : SearchEvent

    data object Settle : SearchEvent
}

private data class SearchProgress(val found: Map<Inet4Address, DiscoveredServer> = emptyMap(), val settled: Boolean = false) {
    fun after(event: SearchEvent): SearchProgress = when (event) {
        is SearchEvent.Service -> copy(found = found.with(event.service))
        is SearchEvent.Host -> copy(found = found.with(event.host))
        SearchEvent.Settle -> copy(settled = true)
    }

    fun search(): ServerSearch {
        val rows = found.values.sortedWith(compareBy(NaturalOrder) { it.name })
        return if (settled) ServerSearch.Settled(rows) else ServerSearch.Searching(rows)
    }
}

/** A DNS-SD service names its row and gives its port; a second service at the same address changes nothing. */
internal fun Map<Inet4Address, DiscoveredServer>.with(service: DnsSdService): Map<Inet4Address, DiscoveredServer> {
    val known = get(service.address)
    val merged = when {
        known == null -> DiscoveredServer(service.name, service.address, service.port, setOf(DiscoverySource.DnsSd), service.host)
        DiscoverySource.DnsSd in known.sources -> known.copy(hostName = known.hostName ?: service.host)
        else -> known.copy(name = service.name, port = service.port, sources = known.sources + DiscoverySource.DnsSd, hostName = service.host ?: known.hostName)
    }
    return this + (service.address to merged)
}

/** A NetBIOS answer only fills in what DNS-SD did not give. */
internal fun Map<Inet4Address, DiscoveredServer>.with(host: NetBiosHost): Map<Inet4Address, DiscoveredServer> {
    val known = get(host.address)
    val merged = when {
        known == null -> DiscoveredServer(host.name ?: host.address.hostAddress.orEmpty(), host.address, SMB_DEFAULT_PORT, setOf(DiscoverySource.NetBios), host.name)
        else -> known.copy(sources = known.sources + DiscoverySource.NetBios, hostName = known.hostName ?: host.name)
    }
    return this + (host.address to merged)
}

/**
 * What the host field gets for a chosen row (docs/spec.md section 4.5): [DiscoveredServer.hostName] when looking it
 * up within [limit] gives the row's address, else the dotted address. The lookup has no timeout of its own, so it runs
 * on [lookups], whose few threads and short queue bound what a lookup that never answers can hold. A lookup that has
 * not started when [limit] passes or the caller is cancelled is taken off the queue and never runs; one that has
 * started is left behind. When the queue is full the answer is the address at once.
 */
suspend fun DiscoveredServer.hostField(
    limit: Duration = HOST_LOOKUP_LIMIT,
    lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    lookups: ThreadPoolExecutor = HostLookups.shared,
): String {
    val dotted = address.hostAddress.orEmpty()
    val name = hostName ?: return dotted
    val answer = CompletableDeferred<List<InetAddress>>()
    val task = FutureTask<Unit> {
        answer.complete(
            try {
                lookup(name)
            } catch (_: Exception) {
                emptyList()
            },
        )
    }
    try {
        lookups.execute(task)
    } catch (_: RejectedExecutionException) {
        return dotted
    }
    val addresses = try {
        withTimeoutOrNull(limit) { answer.await() }.orEmpty()
    } finally {
        if (task.cancel(false)) lookups.remove(task)
    }
    return if (address in addresses) name else dotted
}

val HOST_LOOKUP_LIMIT = 2.seconds

/** The threads and the queue that name lookups of [hostField] share. */
internal object HostLookups {
    const val THREADS = 2
    const val QUEUE = 8

    fun newExecutor(threads: Int = THREADS, queue: Int = QUEUE): ThreadPoolExecutor =
        ThreadPoolExecutor(threads, threads, 10, TimeUnit.SECONDS, ArrayBlockingQueue(queue), { runnable ->
            Thread(runnable, "hirari-host-lookup").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

    val shared: ThreadPoolExecutor by lazy { newExecutor() }
}

private const val SMB_DEFAULT_PORT = 445
