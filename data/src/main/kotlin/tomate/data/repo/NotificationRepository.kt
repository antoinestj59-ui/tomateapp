package tomate.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tomate.core.model.AppError
import tomate.core.model.Outcome
import tomate.data.backend.SupabaseApi

/**
 * Une notification lue sur le serveur (table `notifications`, migration 0007).
 * Le serveur reste la seule source : rien n'est créé côté client.
 */
data class TomateNotification(
    val id: String,
    val kind: String,
    val body: String,
    val actorId: String?,
    val conversationId: String?,
    val entityId: String?,
    val read: Boolean,
    val createdAt: Long,
)

/**
 * Lecture des notifications personnelles et marquage « lu ».
 *
 * Sans la migration 0007, PostgREST renvoie une erreur de table inconnue : elle est
 * traduite en [NotificationAvailability.Missing] pour que l'écran l'annonce au lieu
 * d'afficher une liste vide trompeuse.
 */
class NotificationRepository(private val api: SupabaseApi) {

    enum class Availability { Ready, Missing }

    suspend fun load(limit: Int = 50): Outcome<Pair<Availability, List<TomateNotification>>> {
        val result = api.select(
            table = "notifications",
            query = listOf(
                "select" to "id,kind,body,actor_id,conversation_id,entity_id,read_at,created_at",
                "order" to "created_at.desc",
                "limit" to limit.toString(),
            ),
            single = false,
        )
        return when (result) {
            is Outcome.Failure -> {
                val message = result.error.userMessage
                if (message.contains("PGRST205") || message.contains("does not exist") ||
                    message.contains("n'existe pas") || message.contains("schema cache")
                ) {
                    Outcome.Success(Availability.Missing to emptyList())
                } else {
                    Outcome.Failure(result.error)
                }
            }
            is Outcome.Success -> Outcome.Success(
                Availability.Ready to parse(result.value),
            )
        }
    }

    suspend fun markRead(id: String): Outcome<Unit> = when (
        val result = api.update(
            table = "notifications",
            filter = listOf("id" to "eq.$id"),
            body = """{"read_at":"${java.time.Instant.now()}"}""",
        )
    ) {
        is Outcome.Failure -> Outcome.Failure(result.error)
        is Outcome.Success -> Outcome.Success(Unit)
    }

    suspend fun markAllRead(): Outcome<Unit> = when (
        val result = api.update(
            table = "notifications",
            filter = listOf("read_at" to "is.null"),
            body = """{"read_at":"${java.time.Instant.now()}"}""",
        )
    ) {
        is Outcome.Failure -> Outcome.Failure(result.error)
        is Outcome.Success -> Outcome.Success(Unit)
    }

    private fun parse(raw: String): List<TomateNotification> {
        val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            TomateNotification(
                id = obj.str("id") ?: return@mapNotNull null,
                kind = obj.str("kind") ?: "system",
                body = obj.str("body").orEmpty(),
                actorId = obj.str("actor_id"),
                conversationId = obj.str("conversation_id"),
                entityId = obj.str("entity_id"),
                read = obj.str("read_at") != null,
                createdAt = parseInstant(obj.str("created_at")),
            )
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun parseInstant(value: String?): Long {
        if (value == null) return 0L
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    }

    companion object {
        /** Traduction lisible d'un `kind`, sans invention : le serveur envoie le texte. */
        fun kindLabel(kind: String): String = when (kind) {
            "message" -> "Message"
            "reaction" -> "Réaction"
            "comment" -> "Commentaire"
            "join" -> "Communauté"
            "story" -> "Story"
            else -> "Notification"
        }

        /** Un utilisateur non connecté ne doit jamais déclencher d'appel réseau ici. */
        val unauthenticated: AppError = AppError.Unauthorized()
    }
}
