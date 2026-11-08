package tomate.net.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tomate.core.model.MessageEnvelope
import java.util.Base64

/**
 * Wire format for envelopes exchanged over BLE / Wi-Fi sockets.
 *
 * Framing: one envelope is serialised to JSON, then split into chunks that fit a BLE write.
 * Each frame is `TOMATE1|<messageId>|<index>/<total>|<base64 chunk>`. Receivers reassemble by
 * messageId, discard incomplete groups (callers forget them on timeout), and never trust the
 * declared total: it is bounded by [MAX_FRAMES].
 *
 * `java.util.Base64` is used instead of `android.util.Base64` so the codec is unit-testable on a
 * plain JVM (minSdk is 26, so `java.util.Base64` is always available).
 */
object EnvelopeCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    const val FRAME_PREFIX = "TOMATE1"
    const val MAX_ENVELOPE_BYTES = 64 * 1024
    const val MAX_FRAMES = 512
    const val DEFAULT_CHUNK_SIZE = 400

    @Serializable
    private data class Wire(
        @SerialName("message_id") val messageId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("sender_id") val senderId: String,
        @SerialName("recipient_id") val recipientId: String? = null,
        @SerialName("created_at") val createdAt: Long,
        @SerialName("expires_at") val expiresAt: Long? = null,
        @SerialName("hop_count") val hopCount: Int = 0,
        @SerialName("protocol_version") val protocolVersion: Int,
        val payload: String,
        val signature: String? = null,
    )

    fun encode(envelope: MessageEnvelope): String {
        val wire = Wire(
            messageId = envelope.messageId,
            conversationId = envelope.conversationId,
            senderId = envelope.senderId,
            recipientId = envelope.recipientId,
            createdAt = envelope.createdAt,
            expiresAt = envelope.expiresAt,
            hopCount = envelope.hopCount,
            protocolVersion = envelope.protocolVersion,
            payload = envelope.payload,
            signature = envelope.signature,
        )
        return json.encodeToString(Wire.serializer(), wire)
    }

    fun decode(text: String): MessageEnvelope? = runCatching {
        val wire = json.decodeFromString(Wire.serializer(), text)
        MessageEnvelope(
            messageId = wire.messageId,
            conversationId = wire.conversationId,
            senderId = wire.senderId,
            recipientId = wire.recipientId,
            createdAt = wire.createdAt,
            expiresAt = wire.expiresAt,
            hopCount = wire.hopCount,
            protocolVersion = wire.protocolVersion,
            payload = wire.payload,
            signature = wire.signature,
        )
    }.getOrNull()

    fun encodeBytes(envelope: MessageEnvelope): ByteArray = encode(envelope).toByteArray(Charsets.UTF_8)

    /** Splits an envelope into transport frames. */
    fun frame(envelope: MessageEnvelope, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<String> {
        val encoded = encodeBytes(envelope)
        require(encoded.size <= MAX_ENVELOPE_BYTES) {
            "Enveloppe trop volumineuse (${encoded.size} octets)"
        }
        val base64 = Base64.getEncoder().encodeToString(encoded)
        val chunks = base64.chunked(chunkSize.coerceAtLeast(64))
        return chunks.mapIndexed { index, chunk ->
            "$FRAME_PREFIX|${envelope.messageId}|${index + 1}/${chunks.size}|$chunk"
        }
    }

    /** Reassembles frames. Returns null until the group is complete. */
    class Reassembler {
        private val groups = mutableMapOf<String, MutableMap<Int, String>>()

        fun offer(frame: String): MessageEnvelope? {
            val parts = frame.split('|')
            if (parts.size != 4 || parts[0] != FRAME_PREFIX) return null
            val messageId = parts[1]
            val indexTotal = parts[2].split('/')
            if (indexTotal.size != 2) return null
            val index = indexTotal[0].toIntOrNull() ?: return null
            val total = indexTotal[1].toIntOrNull() ?: return null
            if (total <= 0 || total > MAX_FRAMES || index !in 1..total) return null

            val group = groups.getOrPut(messageId) { mutableMapOf() }
            group[index] = parts[3]
            if (group.size != total) return null

            val builder = StringBuilder()
            for (i in 1..total) builder.append(group[i] ?: return null)
            groups.remove(messageId)
            val bytes = runCatching { Base64.getDecoder().decode(builder.toString()) }.getOrNull()
                ?: return null
            if (bytes.size > MAX_ENVELOPE_BYTES) return null
            return decode(String(bytes, Charsets.UTF_8))
        }

        fun forget(messageId: String) {
            groups.remove(messageId)
        }

        fun clear() {
            groups.clear()
        }
    }
}
