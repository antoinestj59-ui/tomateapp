package tomate.data.extras

import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tomate.core.model.Message

/**
 * Signalisation d'appel, partagée mot pour mot avec la PWA.
 *
 * Un SDP pèse 3 à 6 Ko alors que `messages.body` est limité à 4000 caractères : chaque signal est
 * donc encodé en base64 puis découpé en morceaux numérotés, réassemblés à la lecture. Le format
 * est celui de `web/js/extras.js` :
 *
 *   [[tomate:call:v1]]<id>|<index>|<total>|<base64 du JSON>
 *
 * Un client Android peut donc répondre à un appel lancé depuis le navigateur, et inversement.
 */
@Serializable
data class CallSignal(
    /** `offer`, `answer`, `ice` ou `end`. */
    val kind: String,
    val callId: String,
    /** Présent sur une offre : l'appel demande la vidéo. */
    val video: Boolean? = null,
    val sdp: String? = null,
    /** Candidat ICE tel que le navigateur le sérialise (`candidate`, `sdpMid`, `sdpMLineIndex`). */
    val candidate: JsonObject? = null,
    /** Horodatage de réception, ajouté localement : jamais envoyé. */
    val at: Long? = null,
) {
    val isOffer: Boolean get() = kind == "offer"
    val isAnswer: Boolean get() = kind == "answer"
    val isIce: Boolean get() = kind == "ice"
    val isEnd: Boolean get() = kind == "end"

    /** Candidat ICE lisible par l'API WebRTC native. */
    fun iceParts(): Triple<String?, Int?, String?>? {
        val json = candidate ?: return null
        val sdp = json["candidate"]?.jsonPrimitive?.content ?: return null
        val mid = json["sdpMid"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val line = json["sdpMLineIndex"]?.let {
            runCatching { it.jsonPrimitive.content.toInt() }.getOrNull()
        }
        return Triple(mid, line, sdp)
    }
}

object CallCodec {
    /** Le web découpe à 3000 caractères : on garde exactement la même taille des deux côtés. */
    const val CHUNK = 3000

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(signal: CallSignal): String = json.encodeToString(CallSignal.serializer(), signal)

    fun decode(payload: String): CallSignal? =
        runCatching { json.decodeFromString(CallSignal.serializer(), payload) }.getOrNull()

    /**
     * Découpe un signal en messages ordinaires : ils passent donc par la même file locale, les
     * mêmes règles RLS et le même chiffrement de transport que n'importe quel message.
     */
    fun pack(signal: CallSignal, id: String = UUID.randomUUID().toString()): List<String> {
        val payload = Base64.getEncoder().encodeToString(encode(signal).toByteArray(Charsets.UTF_8))
        val total = maxOf(1, (payload.length + CHUNK - 1) / CHUNK)
        return (0 until total).map { index ->
            val body = payload.substring(index * CHUNK, minOf((index + 1) * CHUNK, payload.length))
            "${ConversationExtras.CALL_PREFIX}$id|$index|$total|$body"
        }
    }

    /**
     * Réassemble les paquets complets reçus des autres participants, dans l'ordre d'arrivée.
     * Un paquet incomplet est ignoré : mieux vaut ne rien faire qu'appliquer un demi-SDP.
     */
    fun collect(messages: List<Message>, meId: String?): List<CallSignal> {
        data class Part(val total: Int, val chunks: MutableMap<Int, String>, val at: Long)

        val packs = LinkedHashMap<String, Part>()
        for (message in messages) {
            if (!ConversationExtras.isCall(message.body)) continue
            if (meId != null && message.senderId == meId) continue
            val raw = message.body.removePrefix(ConversationExtras.CALL_PREFIX)
            val fields = raw.split('|', limit = 4)
            if (fields.size != 4) continue
            val id = fields[0]
            val index = fields[1].toIntOrNull() ?: continue
            val total = fields[2].toIntOrNull() ?: continue
            if (id.isEmpty() || index < 0 || total < 1 || index >= total) continue
            val part = packs.getOrPut(id) { Part(total, mutableMapOf(), message.createdAt) }
            if (part.total != total) continue
            part.chunks[index] = fields[3]
        }

        val signals = mutableListOf<CallSignal>()
        for (part in packs.values) {
            if (part.chunks.size != part.total) continue
            val payload = buildString {
                for (index in 0 until part.total) append(part.chunks[index] ?: "")
            }
            val decoded = runCatching {
                String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
            }.getOrNull() ?: continue
            val signal = decode(decoded) ?: continue
            signals += signal.copy(at = part.at)
        }
        return signals
    }

    /** Candidat ICE au format du navigateur, pour que la PWA le comprenne. */
    fun candidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?): JsonObject =
        buildJsonObject {
            put("candidate", candidate)
            if (sdpMid != null) put("sdpMid", sdpMid)
            if (sdpMLineIndex != null) put("sdpMLineIndex", sdpMLineIndex)
        }
}
