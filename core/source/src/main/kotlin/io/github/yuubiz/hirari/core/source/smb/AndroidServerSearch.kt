package io.github.yuubiz.hirari.core.source.smb

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.annotation.TargetApi
import android.os.Build
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
 * A [WifiManager.MulticastLock] that is not reference counted. Older Android drops the multicast answers of DNS-SD
 * without it, so a search holds it whatever the API level (docs/spec.md section 4.5). Without a Wi-Fi service, or if the
 * system refuses the lock, the search goes on without it.
 */
class WifiMulticastHold(private val wifi: WifiManager?) : MulticastHold {
    private val lock: WifiManager.MulticastLock? by lazy {
        wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply { setReferenceCounted(false) }
    }

    override fun acquire() {
        try {
            lock?.acquire()
        } catch (_: SecurityException) {
        }
    }

    override fun release() {
        try {
            lock?.takeIf { it.isHeld }?.release()
        } catch (_: RuntimeException) {
        }
    }
}

private const val MULTICAST_LOCK_TAG = "hirari-smb-search"

/**
 * `_smb._tcp` over [NsdManager], each service resolved once by name, with [multicast] held while it runs. From API 34
 * a service info callback resolves them side by side; before it, resolves go one at a time across every search of this
 * browser, since the system refuses a second while one is under way.
 */
class NsdBrowser(private val nsd: NsdManager, private val multicast: MulticastHold) : DnsSdBrowser {
    @Suppress("DEPRECATION")
    private val legacy = SerialResolver<NsdServiceInfo> { found, reply ->
        nsd.resolveService(
            found,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val address = info.host as? Inet4Address
                    reply(if (address == null) Resolution.Failed else Resolution.Found(DnsSdService(found.serviceName, host = null, address, info.port)))
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    reply(if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) Resolution.Busy else Resolution.Failed)
                }
            },
        )
    }

    override fun services(): Flow<DnsSdService> = discover().whileHolding(multicast)

    private fun discover(): Flow<DnsSdService> = callbackFlow {
        val seen = HashSet<String>()
        val resolver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) CallbackResolver(nsd) else null
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        if (resolver == null) {
            launch {
                for (info in queue) {
                    val service = legacy.resolve(info)
                    if (service != null) send(service) else synchronized(seen) { seen.remove(info.serviceName) }
                }
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
