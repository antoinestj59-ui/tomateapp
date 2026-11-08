package tomate.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tomate.core.model.MessageEnvelope
import tomate.net.relay.InMemoryRelayStore
import tomate.net.relay.RelayEngine
import tomate.net.transport.EnvelopeCodec

class RelayEngineTest {

    private val now = 1_000_000L

    private fun envelope(
        id: String = "m1",
        recipient: String? = "peer-b",
        hops: Int = 0,
        created: Long = now,
        expires: Long? = now + 60_000,
        payload: String = "bonjour",
        version: Int = MessageEnvelope.PROTOCOL_VERSION,
    ) = MessageEnvelope(
        messageId = id,
        conversationId = "c1",
        senderId = "peer-a",
        recipientId = recipient,
        createdAt = created,
        expiresAt = expires,
        hopCount = hops,
        protocolVersion = version,
        payload = payload,
    )

    private fun engine(
        policy: RelayEngine.RelayPolicy = RelayEngine.RelayPolicy(),
        store: InMemoryRelayStore = InMemoryRelayStore(),
    ) = RelayEngine(store, policy, clock = { now })

    @Test
    fun `accepts envelope for another peer and queues it`() = runTest {
        val store = InMemoryRelayStore()
        val engine = engine(store = store)
        val decision = engine.accept(envelope(recipient = "peer-c"), recipientId = "me")
        assertTrue(decision is RelayEngine.Decision.Forward)
        assertEquals(1, store.count())
    }

    @Test
    fun `envelope addressed to me is delivered and not queued`() = runTest {
        val store = InMemoryRelayStore()
        val engine = engine(store = store)
        val decision = engine.accept(envelope(recipient = "me"), recipientId = "me")
        assertTrue(decision is RelayEngine.Decision.Deliver)
        assertEquals(0, store.count())
    }

    @Test
    fun `duplicate message id is dropped`() = runTest {
        val engine = engine()
        engine.accept(envelope(recipient = "peer-c"), recipientId = "me")
        val second = engine.accept(envelope(recipient = "peer-c"), recipientId = "me")
        assertEquals(
            RelayEngine.Reason.Duplicate,
            (second as RelayEngine.Decision.Drop).reason,
        )
    }

    @Test
    fun `expired envelope is dropped`() = runTest {
        val engine = engine()
        val decision = engine.accept(
            envelope(expires = now - 1),
            recipientId = "me",
        )
        assertEquals(RelayEngine.Reason.Expired, (decision as RelayEngine.Decision.Drop).reason)
    }

    @Test
    fun `hop limit is enforced`() = runTest {
        val engine = engine(policy = RelayEngine.RelayPolicy(maxHops = 2))
        val decision = engine.accept(envelope(hops = 3), recipientId = "me")
        assertEquals(
            RelayEngine.Reason.HopLimitExceeded,
            (decision as RelayEngine.Decision.Drop).reason,
        )
    }

    @Test
    fun `quota is enforced`() = runTest {
        val store = InMemoryRelayStore(maxEntries = 1)
        val engine = engine(store = store)
        engine.accept(envelope(id = "m1", recipient = "peer-c"), recipientId = "me")
        val second = engine.accept(envelope(id = "m2", recipient = "peer-c"), recipientId = "me")
        assertEquals(
            RelayEngine.Reason.QuotaExceeded,
            (second as RelayEngine.Decision.Drop).reason,
        )
    }

    @Test
    fun `forwarding disabled drops without queueing`() = runTest {
        val store = InMemoryRelayStore()
        val engine = engine(policy = RelayEngine.RelayPolicy(forwardingEnabled = false), store = store)
        val decision = engine.accept(envelope(recipient = "peer-c"), recipientId = "me")
        assertEquals(
            RelayEngine.Reason.ForwardingDisabled,
            (decision as RelayEngine.Decision.Drop).reason,
        )
        assertEquals(0, store.count())
    }

    @Test
    fun `unsupported protocol version is rejected`() = runTest {
        val engine = engine()
        val decision = engine.accept(envelope(version = 99), recipientId = "me")
        assertEquals(
            RelayEngine.Reason.UnsupportedVersion,
            (decision as RelayEngine.Decision.Drop).reason,
        )
    }

    @Test
    fun `oversized payload is rejected`() = runTest {
        val engine = engine(policy = RelayEngine.RelayPolicy(maxEnvelopeBytes = 16))
        val decision = engine.accept(envelope(payload = "x".repeat(32)), recipientId = "me")
        assertEquals(RelayEngine.Reason.TooLarge, (decision as RelayEngine.Decision.Drop).reason)
    }

    @Test
    fun `toForward increments hop count and never exceeds policy`() = runTest {
        val engine = engine()
        engine.accept(envelope(recipient = "peer-c"), recipientId = "me")
        val forwardable = engine.toForward()
        assertEquals(1, forwardable.size)
        assertEquals(1, forwardable.first().hopCount)
        engine.recordForwarded(forwardable.first().messageId)
        assertEquals(0, engine.queueSize())
    }

    @Test
    fun `purge removes expired relayed envelopes`() = runTest {
        val store = InMemoryRelayStore()
        var clockNow = now
        val engine = RelayEngine(store, RelayEngine.RelayPolicy(), clock = { clockNow })
        engine.accept(envelope(recipient = "peer-c", expires = now + 1_000), recipientId = "me")
        clockNow = now + 5_000
        assertEquals(0, engine.toForward().size)
        assertEquals(0, store.count())
    }
}

class EnvelopeCodecTest {

    private val envelope = MessageEnvelope(
        messageId = "abc-123",
        conversationId = "conv-1",
        senderId = "user-1",
        recipientId = "user-2",
        createdAt = 1_700_000_000_000,
        expiresAt = 1_700_086_400_000,
        hopCount = 2,
        payload = "contenu du message",
    )

    @Test
    fun `round trip preserves all fields`() {
        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(envelope))
        assertNotNull(decoded)
        assertEquals(envelope, decoded)
    }

    @Test
    fun `framing and reassembly rebuild the envelope`() {
        val reassembler = EnvelopeCodec.Reassembler()
        var result: MessageEnvelope? = null
        EnvelopeCodec.frame(envelope, chunkSize = 64).forEach { frame ->
            result = reassembler.offer(frame) ?: result
        }
        assertEquals(envelope, result)
    }

    @Test
    fun `partial frames do not produce an envelope`() {
        val reassembler = EnvelopeCodec.Reassembler()
        val frames = EnvelopeCodec.frame(envelope, chunkSize = 64)
        assertNull(reassembler.offer(frames.first()))
    }

    @Test
    fun `corrupted frame is rejected`() {
        val reassembler = EnvelopeCodec.Reassembler()
        assertNull(reassembler.offer("NOTOMATE|abc|1/1|zzzz"))
        assertNull(reassembler.offer("TOMATE1|abc|0/1|zzzz"))
        assertNull(reassembler.offer("TOMATE1|abc|1/0|zzzz"))
        assertNull(reassembler.offer("TOMATE1|abc|1/9999|zzzz"))
        assertNull(reassembler.offer("TOMATE1|abc|1/1|!!!!"))
    }
}
