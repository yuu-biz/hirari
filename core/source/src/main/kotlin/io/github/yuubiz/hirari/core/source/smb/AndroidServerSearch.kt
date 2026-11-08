package io.github.yuubiz.hirari.core.source.smb

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.annotation.TargetApi
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.Executor

/** The IPv4 link addresses of the Wi-Fi and Ethernet networks, each with a socket bound to its network. */
class ConnectivityLanNetworks(private val connectivity: ConnectivityManager) : LanNetworks {
    // Deprecated in favour of network callbacks, which give no snapshot to answer at once from.
    @Suppress("DEPRECATION")
    override fun links(): List<LanLink> = connectivity.allNetworks.flatMap { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@flatMap emptyList()
        val lan = !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        if (!lan) return@flatMap emptyList()
        connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { link ->
            (link.address as? Inet4Address)?.let { LanLink(Ipv4Network(it, link.prefixLength)) { network.datagramSocket() } }
        }
    }
}

private fun Network.datagramSocket(): DatagramSocket {
    val socket = DatagramSocket(null as SocketAddress?)
    try {
        socket.broadcast = true
        bindSocket(socket)
        socket.bind(InetSocketAddress(0))
    } catch (e: Exception) {
        socket.close()
        throw e
    }
    return socket
}

/**
 * `_smb._tcp` over [NsdManager], each service resolved once by name. From API 34 a service info callback resolves
 * them side by side; before it, resolves go one at a time, since the system refuses a second while one is under way.
 */
class NsdBrowser(private val nsd: NsdManager) : DnsSdBrowser {
    override fun services(): Flow<DnsSdService> = callbackFlow {
        val seen = HashSet<String>()
        val resolver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) CallbackResolver(nsd) else null
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        if (resolver == null) {
            launch {
                for (info in queue) resolveOnce(nsd, info)?.let { send(it) }
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!synchronized(seen) { seen.add(info.serviceName) }) return
                if (resolver != null) resolver.resolve(info) { trySend(it) } else queue.trySend(info)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(SMB_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
            }
            queue.close()
            resolver?.close()
        }
    }
}

/** Resolves [found] with the API before 34 and waits for its one answer; a late answer after cancellation is dropped. */
@Suppress("DEPRECATION")
private suspend fun resolveOnce(nsd: NsdManager, found: NsdServiceInfo): DnsSdService? {
    val answer = CompletableDeferred<DnsSdService?>()
    nsd.resolveService(
        found,
        object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                answer.complete((info.host as? Inet4Address)?.let { DnsSdService(found.serviceName, host = null, it, info.port) })
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                answer.complete(null)
            }
        },
    )
    return answer.await()
}

@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class CallbackResolver(private val nsd: NsdManager) {
    private val callbacks = mutableListOf<NsdManager.ServiceInfoCallback>()
    private var closed = false

    fun resolve(found: NsdServiceInfo, onResolved: (DnsSdService) -> Unit) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(info: NsdServiceInfo) {
                val address = info.hostAddresses.firstNotNullOfOrNull { it as? Inet4Address } ?: return
                onResolved(DnsSdService(found.serviceName, info.hostNameOrNull(), address, info.port))
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit

            override fun onServiceLost() = Unit

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        synchronized(callbacks) {
            if (closed) return
            callbacks += callback
            nsd.registerServiceInfoCallback(found, DirectExecutor, callback)
        }
    }

    fun close() {
        val registered = synchronized(callbacks) {
            closed = true
            callbacks.toList().also { callbacks.clear() }
        }
        for (callback in registered) {
            try {
                nsd.unregisterServiceInfoCallback(callback)
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}

/** The mDNS host name, which API 36 gives without the `.local` domain the lookup needs. */
private fun NsdServiceInfo.hostNameOrNull(): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
    val name = hostname?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
    return if ('.' in name) name else "$name.local"
}

private object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

private const val SMB_SERVICE_TYPE = "_smb._tcp"
