package tomate.data.extras

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tomate.core.model.Message

/**
 * Sondages, votes et événements, sans nouvelle table.
 *
 * Le web et Android partagent exactement le même protocole, stocké dans le corps d'un message
 * ou d'une publication : les deux clients affichent donc les mêmes sondages et comptent les
 * mêmes votes. Chaque préfixe est versionné pour qu'un ancien client affiche du texte brut au
 * lieu de casser, et pour que le format puisse évoluer sans relire les anciens messages de
 * travers.
 *
 *   [[tomate:poll:v1]]{"q":"…","o":["…","…"]}      question + options
 *   [[tomate:vote:v1]]<message_id>:<option_index>   un vote, une ligne `messages`
 *   [[tomate:event:v1]]{"t":"…","at":"ISO","p":"…"} l'événement est une publication
 *
 * Les votes ne s'affichent jamais comme des messages : ils existent en base pour être partagés
 * entre appareils, mais le rendu les consomme pour compter.
 */
object ConversationExtras {
    const val POLL_PREFIX = "[[tomate:poll:v1]]"
    const val VOTE_PREFIX = "[[tomate:vote:v1]]"
    const val EVENT_PREFIX = "[[tomate:event:v1]]"
    const val CALL_PREFIX = "[[tomate:call:v1]]"

    const val MAX_OPTIONS = 6
    const val MIN_OPTIONS = 2

    /**
     * Signalisation d'appel : le web s'en sert pour négocier, Android ne sait pas encore
     * répondre à un appel. Ces messages sont donc masqués au lieu d'être affichés en JSON brut.
     */
    fun isCall(body: String): Boolean = body.startsWith(CALL_PREFIX)

    internal val json = Json { ignoreUnknownKeys = true }
}

data class Poll(val question: String, val options: List<String>)

data class PollVote(val pollId: String, val option: Int)

/** Dépouillement : un votant ne compte qu'une fois, son vote le plus récent l'emporte. */
data class PollTally(
    val counts: Map<Int, Int>,
    val total: Int,
    val mine: Int?,
) {
    fun countFor(index: Int): Int = counts[index] ?: 0

    /** Pourcentage arrondi, 0 quand personne n'a encore voté. */
    fun percentFor(index: Int): Int =
        if (total == 0) 0 else Math.round(countFor(index) * 100f / total)
}

data class EventInfo(
    val title: String,
    val startsAt: String,
    val place: String,
    val details: String,
) {
    /** Instant de début, ou null si la date stockée est illisible. */
    val instant: Instant? get() = runCatching { Instant.parse(startsAt) }.getOrNull()
}

object PollCodec {

    fun encodePoll(question: String, options: List<String>): String? {
        val cleanedQuestion = question.trim()
        val cleanedOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanedQuestion.isEmpty()) return null
        if (cleanedOptions.size < ConversationExtras.MIN_OPTIONS) return null
        val payload = buildJsonObject {
            put("q", cleanedQuestion)
            putJsonArray("o") { cleanedOptions.take(ConversationExtras.MAX_OPTIONS).forEach { add(it) } }
        }
        return ConversationExtras.POLL_PREFIX + payload.toString()
    }

    fun decodePoll(body: String): Poll? {
        if (!body.startsWith(ConversationExtras.POLL_PREFIX)) return null
        val payload = runCatching {
            ConversationExtras.json.parseToJsonElement(body.removePrefix(ConversationExtras.POLL_PREFIX)).jsonObject
        }.getOrNull() ?: return null
        val question = runCatching { payload["q"]?.jsonPrimitive?.content }.getOrNull()
        val rawOptions = runCatching { payload["o"]?.jsonArray }.getOrNull() ?: return null
        val options = rawOptions
            .mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(ConversationExtras.MAX_OPTIONS)
        if (question.isNullOrBlank() || options.isEmpty()) return null
        return Poll(question = question, options = options)
    }

    fun encodeVote(pollId: String, index: Int): String? {
        if (pollId.isBlank() || index < 0) return null
        return ConversationExtras.VOTE_PREFIX + "$pollId:$index"
    }

    fun isVote(body: String): Boolean = body.startsWith(ConversationExtras.VOTE_PREFIX)

    fun decodeVote(body: String): PollVote? {
        if (!isVote(body)) return null
        val raw = body.removePrefix(ConversationExtras.VOTE_PREFIX)
        val separator = raw.lastIndexOf(':')
        if (separator <= 0) return null
        val pollId = raw.substring(0, separator)
        val option = raw.substring(separator + 1).toIntOrNull() ?: return null
        if (pollId.isBlank() || option < 0) return null
        return PollVote(pollId, option)
    }

    /**
     * Compte les votes d'un sondage à partir des messages de la conversation, du plus ancien au
     * plus récent. `mine` est l'option retenue pour l'utilisateur courant, ou null s'il n'a pas
     * voté. Aucun vote n'est inventé : un message illisible est ignoré.
     */
    fun tally(messages: List<Message>, pollId: String, meId: String?): PollTally {
        val latest = LinkedHashMap<String, Pair<Int, String>>()
        messages
            .filter { it.body.startsWith(ConversationExtras.VOTE_PREFIX) }
            .sortedBy { it.createdAt }
            .forEach { message ->
                val vote = decodeVote(message.body) ?: return@forEach
                if (vote.pollId != pollId) return@forEach
                val previous = latest[message.senderId]
                val stamp = message.createdAt.toString()
                // À date égale, le premier vote lu est conservé, comme côté web.
                if (previous != null && previous.second > stamp) return@forEach
                latest[message.senderId] = vote.option to stamp
            }
        val counts = mutableMapOf<Int, Int>()
        latest.values.forEach { (option, _) -> counts[option] = (counts[option] ?: 0) + 1 }
        return PollTally(counts = counts, total = latest.size, mine = meId?.let { latest[it]?.first })
    }
}

object EventCodec {

    fun encodeEvent(title: String, startsAtIso: String, place: String = "", details: String = ""): String? {
        val cleanedTitle = title.trim()
        if (cleanedTitle.isEmpty()) return null
        if (runCatching { Instant.parse(startsAtIso) }.isFailure) return null
        val payload = buildJsonObject {
            put("t", cleanedTitle)
            put("at", startsAtIso)
            put("p", place.trim())
            put("d", details.trim())
        }
        return ConversationExtras.EVENT_PREFIX + payload.toString()
    }

    fun decodeEvent(body: String): EventInfo? {
        if (!body.startsWith(ConversationExtras.EVENT_PREFIX)) return null
        val payload = runCatching {
            ConversationExtras.json.parseToJsonElement(body.removePrefix(ConversationExtras.EVENT_PREFIX)).jsonObject
        }.getOrNull() ?: return null
        val title = runCatching { payload["t"]?.jsonPrimitive?.content }.getOrNull() ?: return null
        val startsAt = runCatching { payload["at"]?.jsonPrimitive?.content }.getOrNull() ?: return null
        if (title.isBlank() || runCatching { Instant.parse(startsAt) }.isFailure) return null
        val place = runCatching { payload["p"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val details = runCatching { payload["d"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        return EventInfo(title = title, startsAt = startsAt, place = place, details = details)
    }

    fun isEvent(body: String): Boolean = body.startsWith(ConversationExtras.EVENT_PREFIX)
}
