package tomate.net.relay

import tomate.core.model.MessageEnvelope

/**
 * Storage used by the relay engine. Kept as an interface so the engine is fully unit-testable and
 * so the app can back it with Room later without touching the policy code.
 */
interface RelayStore {
    /** Returns false when the quota would be exceeded (the envelope is then dropped). */
    suspend fun store(envelope: MessageEnvelope): Boolean

    suspend fun isSeen(messageId: String, now: Long): Boolean

    suspend fun markSeen(messageId: String, expiresAt: Long)

    suspend fun pending(now: Long, limit: Int): List<MessageEnvelope>

    suspend fun remove(messageId: String)

    suspend fun count(): Int

    suspend fun purge(now: Long)
}

/**
 * In-memory relay store. The relay queue is intentionally ephemeral: TOMATE never writes relayed
 * content to disk, which keeps the "a relay cannot read or keep your messages" promise honest and
 * bounds storage on constrained devices.
 */
class InMemoryRelayStore(
    private val maxEntries: Int = 200,
) : RelayStore {
    private val queue = LinkedHashMap<String, MessageEnvelope>()
    private val seen = HashMap<String, Long>()

    override suspend fun store(envelope: MessageEnvelope): Boolean {
        if (queue.size >= maxEntries && !queue.containsKey(envelope.messageId)) return false
        queue[envelope.messageId] = envelope
        return true
    }

    override suspend fun isSeen(messageId: String, now: Long): Boolean {
        val expiry = seen[messageId] ?: return false
        return expiry > now
    }

    override suspend fun markSeen(messageId: String, expiresAt: Long) {
        seen[messageId] = if (expiresAt <= 0) Long.MAX_VALUE else expiresAt
    }

    override suspend fun pending(now: Long, limit: Int): List<MessageEnvelope> =
        queue.values
            .filter { (it.expiresAt ?: Long.MAX_VALUE) > now }
            .take(limit)

    override suspend fun remove(messageId: String) {
        queue.remove(messageId)
    }

    override suspend fun count(): Int = queue.size

    override suspend fun purge(now: Long) {
        queue.entries.removeAll { (it.value.expiresAt ?: Long.MAX_VALUE) <= now }
        seen.entries.removeAll { it.value <= now }
    }
}
