package tomate.net.transport.wifi

import android.Manifest
import android.content.Context
import android.os.Build
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import tomate.core.model.MessageEnvelope
import tomate.core.model.NearbyPeer
import tomate.core.model.Outcome
import tomate.core.model.TransportKind
import tomate.net.transport.IncomingEnvelope
import tomate.net.transport.EnvelopeCodec
import tomate.net.transport.NearbyTransport
import tomate.net.transport.TransportAvailability

/**
 * Wi-Fi local transport: NSD (DNS-SD) discovery + a TCP socket per transfer.
 *
 * Why local Wi-Fi and not Wi-Fi Direct: NSD works on every Android 8+ device without vendor
 * extensions, does not create a network, and keeps the permission surface small
 * (`NEARBY_WIFI_DEVICES` on API 33+, no location). Wi-Fi Direct is documented as a future
 * enhancement in docs/02_ARCHITECTURE.md.
 *
 * HONEST STATUS: this code is real but has NOT been exercised on physical devices from the cloud
 * build environment. It must be validated on two real phones (see docs/06_TESTS.md).
 */
class WifiLocalTransport(private val context: Context) : NearbyTransport {

    override val kind: TransportKind = TransportKind.WifiLocal

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _availability = MutableStateFlow(TransportAvailability.Stopped)
    override val availability: StateFlow<TransportAvailability> = _availability.asStateFlow()

    private val _peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    override val peers: StateFlow<List<NearbyPeer>> = _peers.asStateFlow()

    private val incomingFlow = MutableSharedFlow<IncomingEnvelope>(extraBufferCapacity = 32)
    private val reassembler = EnvelopeCodec.Reassembler()

    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(NsdManager::class.java)

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serverSocket: ServerSocket? = null
    private var serviceName: String = ""
    private var localDisplayName: String = ""
    private val resolved = mutableMapOf<String, NsdServiceInfo>()

    override fun incoming(): Flow<IncomingEnvelope> = incomingFlow.asSharedFlow()

    /** NSD needs NEARBY_WIFI_DEVICES from API 33 and the location permission before that. */
    fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 33) {
        listOf(android.Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    override suspend fun startSharing(displayName: String): Outcome<Unit> = withContext(Dispatchers.IO) {
        val manager = nsdManager ?: run {
            _availability.value = TransportAvailability.Unsupported
            return@withContext Outcome.Failure(
                tomate.core.model.AppError.Validation(
                    "nsd_unavailable",
                    "La découverte Wi-Fi locale n'est pas disponible sur cet appareil.",
                ),
            )
        }
        if (_availability.value == TransportAvailability.Ready) return@withContext Outcome.Success(Unit)

        localDisplayName = displayName.take(24)
        return@withContext try {
            val socket = ServerSocket(0)
            serverSocket = socket
            serviceName = "tomate-${UUID.randomUUID().toString().take(8)}"

            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceName
                serviceType = SERVICE_TYPE
                port = socket.localPort
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    serviceName = info.serviceName
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    _availability.value = TransportAvailability.Unsupported
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
            registrationListener = listener
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)

            startDiscovery(manager)
            scope.launch { acceptLoop(socket) }
            _availability.value = TransportAvailability.Ready
            Outcome.Success(Unit)
        } catch (t: Throwable) {
            _availability.value = TransportAvailability.Unsupported
            Outcome.Failure(
                tomate.core.model.AppError.Unknown(
                    "Impossible de démarrer le partage Wi-Fi local: ${t.message}",
                ),
            )
        }
    }

    private fun startDiscovery(manager: NsdManager) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == serviceName) return
                if (info.serviceType != SERVICE_TYPE) return
                @Suppress("DEPRECATION")
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolved[serviceInfo.serviceName] = serviceInfo
                        val host = serviceInfo.host?.hostAddress ?: return
                        val peerId = peerIdFor(serviceInfo.serviceName)
                        val peer = NearbyPeer(
                            peerId = peerId,
                            displayName = peerId,
                            transport = TransportKind.WifiLocal,
                            lastSeenAt = System.currentTimeMillis(),
                        )
                        _peers.value = (_peers.value.filterNot { it.peerId == peerId } + peer)
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                resolved.remove(info.serviceName)
                val peerId = peerIdFor(info.serviceName)
                _peers.value = _peers.value.filterNot { it.peerId == peerId }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _availability.value = TransportAvailability.Unsupported
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            scope.launch { handleIncoming(client) }
        }
    }

    private fun handleIncoming(client: Socket) {
        client.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                val envelope = reassembler.offer(line) ?: continue
                incomingFlow.tryEmit(
                    IncomingEnvelope(
                        envelope = envelope,
                        fromPeerId = envelope.senderId,
                        transport = TransportKind.WifiLocal,
                    ),
                )
            }
        }
    }

    override suspend fun stopSharing() {
        withContext(Dispatchers.IO) {
            runCatching { serverSocket?.close() }
            serverSocket = null
            discoveryListener?.let { runCatching { nsdManager?.stopServiceDiscovery(it) } }
            discoveryListener = null
            registrationListener?.let { runCatching { nsdManager?.unregisterService(it) } }
            registrationListener = null
            resolved.clear()
            _peers.value = emptyList()
            _availability.value = TransportAvailability.Stopped
        }
    }

    /** NSD pairing is implicit (DNS-SD announces the service); confirmation is a UI step. */
    override suspend fun pair(peerId: String): Outcome<Unit> =
        if (resolved.values.any { peerIdFor(it.serviceName) == peerId }) {
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(tomate.core.model.AppError.NotFound("Pair introuvable"))
        }

    override suspend fun unpair(peerId: String) {
        _peers.value = _peers.value.filterNot { it.peerId == peerId }
    }

    override suspend fun send(peerId: String, envelope: MessageEnvelope): Outcome<Unit> =
        withContext(Dispatchers.IO) {
            val info = resolved.values.firstOrNull { peerIdFor(it.serviceName) == peerId }
                ?: return@withContext Outcome.Failure(
                    tomate.core.model.AppError.Validation("unknown_peer", "Appareil introuvable."),
                )
            val host = info.host ?: return@withContext Outcome.Failure(
                tomate.core.model.AppError.Unknown("Hôte Wi-Fi inconnu"),
            )
            try {
                withTimeout(SEND_TIMEOUT_MS) {
                    Socket(host, info.port).use { socket ->
                        val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                        EnvelopeCodec.frame(envelope).forEach { frame ->
                            writer.write(frame)
                            writer.write("\n")
                        }
                        writer.flush()
                    }
                }
                Outcome.Success(Unit)
            } catch (t: Throwable) {
                Outcome.Failure(
                    tomate.core.model.AppError.Unknown("Envoi Wi-Fi échoué: ${t.message}"),
                )
            }
        }

    private fun peerIdFor(serviceName: String): String =
        serviceName.removePrefix("tomate-").take(8).ifBlank { serviceName.take(8) }

    private fun NsdServiceInfo.hostSafe(): InetAddress? = runCatching { host }.getOrNull()

    companion object {
        const val SERVICE_TYPE = "_tomate._tcp."
        private const val READ_TIMEOUT_MS = 30_000
        private const val SEND_TIMEOUT_MS = 20_000L
    }
}
