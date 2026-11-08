package tomate.net.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tomate.core.model.MessageEnvelope
import tomate.core.model.NearbyPeer
import tomate.core.model.TransportKind
import tomate.core.model.Outcome

/**
 * Transport-agnostic contract. Business code never touches a radio API directly: BLE, Wi-Fi local
 * and Internet all implement this interface, so the messaging layer can stay identical and the
 * relay engine can treat every medium the same way.
 */
interface NearbyTransport {
    val kind: TransportKind

    /** Whether the radio/permission set is usable right now. */
    val availability: StateFlow<TransportAvailability>

    /** Peers currently in radio range (ephemeral, never persisted). */
    val peers: StateFlow<List<NearbyPeer>>

    suspend fun startSharing(displayName: String): Outcome<Unit>

    suspend fun stopSharing()

    /** Initiates a connection to a discovered peer and confirms the displayed identity. */
    suspend fun pair(peerId: String): Outcome<Unit>

    suspend fun unpair(peerId: String)

    suspend fun send(peerId: String, envelope: MessageEnvelope): Outcome<Unit>

    /** Envelopes received from peers. Collected by the relay/messaging layer. */
    fun incoming(): Flow<IncomingEnvelope>
}

data class IncomingEnvelope(
    val envelope: MessageEnvelope,
    val fromPeerId: String,
    val transport: TransportKind,
)

enum class TransportAvailability {
    /** Radio available and sharing active. */
    Ready,

    /** Sharing stopped by the user. */
    Stopped,

    /** Missing runtime permission. */
    PermissionMissing,

    /** Hardware or system feature unavailable. */
    Unsupported,

    /** Bluetooth/Wi-Fi switched off. */
    Disabled,

    Unknown,
}

val TransportAvailability.label: String
    get() = when (this) {
        TransportAvailability.Ready -> "Actif"
        TransportAvailability.Stopped -> "Inactif"
        TransportAvailability.PermissionMissing -> "Autorisation requise"
        TransportAvailability.Unsupported -> "Non pris en charge"
        TransportAvailability.Disabled -> "Radio désactivée"
        TransportAvailability.Unknown -> "Inconnu"
    }
