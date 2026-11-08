package tomate.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import tomate.core.model.NetworkStatus

/**
 * Real connectivity signal. TOMATE never assumes the network is available: the UI shows the
 * truth and queueing decisions follow this flow.
 */
class NetworkMonitor(private val context: Context) {

    fun status(): Flow<NetworkStatus> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            trySend(NetworkStatus.Offline)
            awaitClose { }
            return@callbackFlow
        }

        fun current(): NetworkStatus {
            val network = manager.activeNetwork ?: return NetworkStatus.Offline
            val caps = manager.getNetworkCapabilities(network) ?: return NetworkStatus.Offline
            val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            return if (usable) NetworkStatus.Online else NetworkStatus.Offline
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(current())
            }

            override fun onLost(network: Network) {
                trySend(current())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(current())
            }
        }

        trySend(NetworkStatus.Checking)
        trySend(current())
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
