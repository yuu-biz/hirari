package io.github.yuubiz.hirari.core.source.smb

import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits whenever a default network becomes available, for the `networkAvailable` of [SmbServer]
 * (docs/spec.md section 6.5). Needs `ACCESS_NETWORK_STATE`.
 */
fun ConnectivityManager.networkAvailable(): Flow<Unit> = callbackFlow {
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(Unit)
        }
    }
    registerDefaultNetworkCallback(callback)
    awaitClose { unregisterNetworkCallback(callback) }
}
