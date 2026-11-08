package tomate.app.ui.nearby

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tomate.core.model.AppError
import tomate.core.model.MessageEnvelope
import tomate.core.model.NearbyPeer
import tomate.core.model.Outcome
import tomate.core.model.TransportKind
import tomate.data.container.TomateContainer
import tomate.net.relay.InMemoryRelayStore
import tomate.net.relay.RelayEngine
import tomate.net.transport.IncomingEnvelope
import tomate.net.transport.NearbyTransport
import tomate.net.transport.TransportAvailability
import tomate.net.transport.ble.BleTransport
import tomate.net.transport.wifi.WifiLocalTransport

/**
 * Proximity screen state. Owns the two real transports and the relay engine.
 *
 * Everything here is a genuine radio path: no simulated transfer. The cloud build environment has
 * no radio and no second phone, so the delivery itself is reported as "untested on hardware".
 */
class NearbyViewModel(
    application: Application,
    private val container: TomateContainer,
) : AndroidViewModel(application) {

    data class UiPeer(
        val peer: NearbyPeer,
        val transportLabel: String,
    )

    data class UiState(
        val sharing: Boolean = false,
        val bleAvailability: TransportAvailability = TransportAvailability.Stopped,
        val wifiAvailability: TransportAvailability = TransportAvailability.Stopped,
        val peers: List<UiPeer> = emptyList(),
        val permissionRequired: List<String> = emptyList(),
        val received: List<Pair<String, String>> = emptyList(),
        val log: List<String> = emptyList(),
        val composingText: String = "",
        val relayEnabled: Boolean = true,
        val batterySaver: Boolean = false,
        val relayQueue: Int = 0,
        val error: AppError? = null,
        val myId: String? = null,
    )

    private val ble: BleTransport = BleTransport(application)
    private val wifi: WifiLocalTransport = WifiLocalTransport(application)

    private val relay = RelayEngine(
        store = InMemoryRelayStore(),
        policy = RelayEngine.RelayPolicy(forwardingEnabled = true),
    )

    private val sharing = MutableStateFlow(false)
    private val composing = MutableStateFlow("")
    private val received = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private val log = MutableStateFlow<List<String>>(emptyList())
    private val error = MutableStateFlow<AppError?>(null)
    private val relayQueue = MutableStateFlow(0)
    private val permissionRequired = MutableStateFlow<List<String>>(emptyList())

    private val transports: List<NearbyTransport> get() = listOf(ble, wifi)

    val state: StateFlow<UiState> = combine(
        combine(ble.availability, wifi.availability) { b, w -> b to w },
        combine(ble.peers, wifi.peers) { b, w ->
            b.map { UiPeer(it, "Bluetooth LE") } + w.map { UiPeer(it, "Wi-Fi local") }
        },
        combine(sharing, combine(received, log) { r, l -> r to l }) { s, rl -> Triple(s, rl.first, rl.second) },
        combine(composing, error) { c, e -> c to e },
        combine(container.settings.settings, relayQueue) { settings, queue ->
            Triple(settings.relayEnabled, settings.batterySaver, queue)
        },
    ) { availabilityPair, peers, sharingTriple, composingPair, settingsTriple ->
        UiState(
            sharing = sharingTriple.first,
            bleAvailability = availabilityPair.first,
            wifiAvailability = availabilityPair.second,
            peers = peers,
            permissionRequired = permissionRequired.value,
            received = sharingTriple.second,
            log = sharingTriple.third,
            composingText = composingPair.first,
            error = composingPair.second,
            relayEnabled = settingsTriple.first,
            batterySaver = settingsTriple.second,
            relayQueue = settingsTriple.third,
            myId = container.session.currentUserId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        collectIncoming()
    }

    fun requiredPermissions(): List<String> = (ble.requiredPermissions() + wifi.requiredPermissions()).distinct()

    fun onPermissionsResult(granted: Boolean) {
        permissionRequired.value = if (granted) emptyList() else requiredPermissions()
        if (granted) startSharing()
    }

    fun onComposingChange(text: String) {
        composing.value = text.take(1000)
    }

    fun startSharing() {
        viewModelScope.launch {
            val displayName = container.database.profileDao().me()?.handle ?: "tomate"
            val bleResult = ble.startSharing(displayName)
            val wifiResult = wifi.startSharing(displayName)
            if (bleResult is Outcome.Failure) {
                appendLog("Bluetooth : ${bleResult.error.userMessage}")
            }
            if (wifiResult is Outcome.Failure) {
                appendLog("Wi-Fi : ${wifiResult.error.userMessage}")
            }
            sharing.value = bleResult is Outcome.Success || wifiResult is Outcome.Success
            if (!sharing.value) {
                error.value = (bleResult as? Outcome.Failure)?.error ?: (wifiResult as? Outcome.Failure)?.error
            }
        }
    }

    fun stopSharing() {
        viewModelScope.launch {
            transports.forEach { runCatching { it.stopSharing() } }
            sharing.value = false
        }
    }

    fun pair(peer: UiPeer) {
        viewModelScope.launch {
            val transport = transportFor(peer.peer.transport) ?: return@launch
            when (val result = transport.pair(peer.peer.peerId)) {
                is Outcome.Success -> appendLog("Appairé avec ${peer.peer.displayName} (${peer.transportLabel})")
                is Outcome.Failure -> error.value = result.error
            }
        }
    }

    fun send(peer: UiPeer) {
        val text = composing.value.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val transport = transportFor(peer.peer.transport) ?: return@launch
            val myId = container.session.currentUserId ?: "local"
            val envelope = MessageEnvelope(
                messageId = java.util.UUID.randomUUID().toString(),
                conversationId = "nearby",
                senderId = myId,
                recipientId = peer.peer.peerId,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + MessageEnvelope.DEFAULT_TTL_MS,
                payload = text,
            )
            when (val result = transport.send(peer.peer.peerId, envelope)) {
                is Outcome.Success -> {
                    composing.value = ""
                    appendLog("Envoyé à ${peer.peer.displayName} via ${peer.transportLabel}")
                }
                is Outcome.Failure -> error.value = result.error
            }
        }
    }

    /** Forwards everything the relay engine has queued for another device. */
    fun forwardQueue(peer: UiPeer) {
        viewModelScope.launch {
            val transport = transportFor(peer.peer.transport) ?: return@launch
            val queued = relay.toForward()
            if (queued.isEmpty()) {
                appendLog("Aucune enveloppe à relayer.")
                return@launch
            }
            var forwarded = 0
            queued.forEach { envelope ->
                if (transport.send(peer.peer.peerId, envelope) is Outcome.Success) {
                    relay.recordForwarded(envelope.messageId)
                    forwarded++
                }
            }
            relayQueue.value = relay.queueSize()
            appendLog("$forwarded enveloppe(s) relayée(s) vers ${peer.peer.displayName}.")
        }
    }

    fun setRelayEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setRelayEnabled(enabled) }
    }

    fun setBatterySaver(enabled: Boolean) {
        viewModelScope.launch { container.settings.setBatterySaver(enabled) }
    }

    fun dismissError() {
        error.value = null
    }

    private fun collectIncoming() {
        transports.forEach { transport ->
            viewModelScope.launch {
                transport.incoming().collect { incoming -> handleIncoming(incoming) }
            }
        }
    }

    private suspend fun handleIncoming(incoming: IncomingEnvelope) {
        val myId = container.session.currentUserId
        when (val decision = relay.accept(incoming.envelope, myId)) {
            is RelayEngine.Decision.Deliver -> {
                received.value = (listOf(incoming.envelope.payload to incoming.transport.name) + received.value).take(50)
                appendLog("Message reçu de ${incoming.fromPeerId.take(6)} via ${incoming.transport.name}")
            }
            is RelayEngine.Decision.Forward -> {
                relayQueue.value = relay.queueSize()
                appendLog("Enveloppe mise en relais (${incoming.envelope.messageId.take(6)}).")
            }
            is RelayEngine.Decision.Drop -> {
                appendLog("Enveloppe ignorée : ${decision.reason.name}.")
            }
        }
    }

    private fun transportFor(kind: TransportKind): NearbyTransport? = when (kind) {
        TransportKind.BluetoothLe -> ble
        TransportKind.WifiLocal -> wifi
        else -> null
    }

    private fun appendLog(message: String) {
        log.value = (listOf(message) + log.value).take(30)
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            transports.forEach { runCatching { it.stopSharing() } }
        }
    }
}
