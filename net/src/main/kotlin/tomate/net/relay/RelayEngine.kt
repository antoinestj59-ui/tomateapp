package tomate.net.relay

import tomate.core.model.MessageEnvelope

/**
 * Opportunistic relay engine.
 *
 * Rules implemented here (and covered by unit tests):
 *  - only authenticated, size-bounded envelopes are accepted;
 *  - an envelope addressed to this device is delivered locally, never forwarded;
 *  - duplicates are dropped by message id (loop and storm protection);
 *  - hop count and TTL are enforced before storing;
 *  - storage has a hard quota, so a relay can never fill the device;
 *  - the user can disable forwarding entirely (battery/privacy).
 *
 * A relay never inspects `payload`: it is opaque (ciphertext once E2EE exists, UTF-8 today).
 * Delivery is best-effort by design: no guarantee is ever promised to the user.
 */
class RelayEngine(
    private val store: RelayStore,
    private val policy: RelayPolicy = RelayPolicy(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class RelayPolicy(
        val maxHops: Int = MessageEnvelope.MAX_HOPS,
        val ttlMs: Long = MessageEnvelope.DEFAULT_TTL_MS,
        val maxEnvelopeBytes: Int = 64 * 1024,
        val forwardingEnabled: Boolean = true,
    )

    sealed interface Decision {
        /** Addressed to this device: hand it to the messaging layer. */
        data class Deliver(val envelope: MessageEnvelope) : Decision

        /** Accepted for forwarding to another device. */
        data class Forward(val envelope: MessageEnvelope) : Decision

        data class Drop(val reason: Reason, val messageId: String) : Decision
    }

    enum class Reason {
        Duplicate,
        Expired,
        HopLimitExceeded,
        QuotaExceeded,
        TooLarge,
        UnsupportedVersion,
        ForwardingDisabled,
        Malformed,
    }

    /**
     * @param recipientId the local account id, or null when unknown (offline / no session).
     */
    suspend fun accept(envelope: MessageEnvelope, recipientId: String?): Decision {
        val now = clock()

        if (envelope.protocolVersion != MessageEnvelope.PROTOCOL_VERSION) {
            return Decision.Drop(Reason.UnsupportedVersion, envelope.messageId)
        }
        if (envelope.messageId.isBlank() || envelope.payload.isBlank()) {
            return Decision.Drop(Reason.Malformed, envelope.messageId)
        }
        if (envelope.payload.length > policy.maxEnvelopeBytes) {
            return Decision.Drop(Reason.TooLarge, envelope.messageId)
        }
        if (envelope.hopCount > policy.maxHops) {
            return Decision.Drop(Reason.HopLimitExceeded, envelope.messageId)
        }
        val createdAt = envelope.createdAt.takeIf { it > 0 } ?: now
        val expiresAt = envelope.expiresAt ?: (createdAt + policy.ttlMs)
        if (expiresAt <= now) {
            return Decision.Drop(Reason.Expired, envelope.messageId)
        }
        if (store.isSeen(envelope.messageId, now)) {
            return Decision.Drop(Reason.Duplicate, envelope.messageId)
        }
        store.markSeen(envelope.messageId, expiresAt)

        val addressedToMe = recipientId != null && envelope.recipientId == recipientId
        if (addressedToMe) {
            return Decision.Deliver(envelope)
        }
        if (!policy.forwardingEnabled) {
            return Decision.Drop(Reason.ForwardingDisabled, envelope.messageId)
        }
        if (envelope.hopCount >= policy.maxHops) {
            return Decision.Drop(Reason.HopLimitExceeded, envelope.messageId)
        }
        val stored = store.store(envelope.copy(expiresAt = expiresAt))
        return if (stored) {
            Decision.Forward(envelope)
        } else {
            Decision.Drop(Reason.QuotaExceeded, envelope.messageId)
        }
    }

    /** Envelopes ready to be handed to a transport, with hop count already incremented. */
    suspend fun toForward(limit: Int = 10): List<MessageEnvelope> {
        if (!policy.forwardingEnabled) return emptyList()
        val now = clock()
        store.purge(now)
        return store.pending(now, limit).map { it.copy(hopCount = it.hopCount + 1) }
    }

    /** Called once an envelope has actually been handed to a transport. */
    suspend fun recordForwarded(messageId: String) {
        store.remove(messageId)
    }

    suspend fun queueSize(): Int = store.count()

    fun policy(): RelayPolicy = policy
}
