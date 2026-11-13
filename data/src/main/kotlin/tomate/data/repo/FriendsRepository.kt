package tomate.data.repo

import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import tomate.core.model.AppError
import tomate.core.model.Outcome
import tomate.data.backend.SessionManager
import tomate.data.backend.SupabaseApi
import tomate.data.backend.dto.FriendRequestDto
import tomate.data.backend.dto.ProfileDto

/**
 * Une demande d'amis telle qu'elle existe sur le serveur (migration 0012).
 * [otherId] est l'autre personne, quelle que soit la direction : l'écran n'a pas à recalculer.
 */
data class FriendRequest(
    val id: String,
    val fromId: String,
    val toId: String,
    val otherId: String,
    val otherHandle: String,
    val otherName: String,
    val note: String,
    val status: String,
    val outgoing: Boolean,
    val createdAt: Long,
) {
    val pending: Boolean get() = status == "Pending"
    val accepted: Boolean get() = status == "Accepted"
    val otherLabel: String get() = otherName.ifBlank { if (otherHandle.isBlank()) "Contact" else "@$otherHandle" }
}

enum class FriendsAvailability { Ready, Missing }

data class FriendsSnapshot(
    val availability: FriendsAvailability,
    val requests: List<FriendRequest>,
)

/**
 * Demandes d'amis : aucune donnée inventée, aucune liste locale fabriquée.
 *
 * Le serveur est la seule source. Sans la migration 0012, PostgREST répond « table inconnue » :
 * l'écran doit alors l'annoncer au lieu d'afficher « aucune demande ».
 */
class FriendsRepository(
    private val api: SupabaseApi,
    private val session: SessionManager,
) {

    suspend fun load(limit: Int = 100): Outcome<FriendsSnapshot> {
        val me = session.currentUserId ?: return Outcome.Failure(AppError.Unauthorized())
        val response = api.select(
            table = "friend_requests",
            query = listOf(
                "select" to "id,from_id,to_id,note,status,created_at",
                "or" to "(from_id.eq.$me,to_id.eq.$me)",
                "order" to "created_at.desc",
                "limit" to limit.toString(),
            ),
            single = false,
        )
        val raw = when (response) {
            is Outcome.Failure -> {
                return if (isMissingTable(response.error)) {
                    Outcome.Success(FriendsSnapshot(FriendsAvailability.Missing, emptyList()))
                } else {
                    response
                }
            }
            is Outcome.Success -> response.value
        }

        val rows = runCatching {
            SupabaseApi.json.decodeFromString(ListSerializer(FriendRequestDto.serializer()), raw)
        }.getOrElse {
            return Outcome.Failure(AppError.Unknown("Demandes d'amis illisibles"))
        }
        if (rows.isEmpty()) {
            return Outcome.Success(FriendsSnapshot(FriendsAvailability.Ready, emptyList()))
        }

        val profiles = profilesByIds(rows.flatMap { listOf(it.fromId, it.toId) }.toSet() - me)
        val requests = rows.map { dto ->
            val outgoing = dto.fromId == me
            val otherId = if (outgoing) dto.toId else dto.fromId
            val profile = profiles[otherId]
            FriendRequest(
                id = dto.id,
                fromId = dto.fromId,
                toId = dto.toId,
                otherId = otherId,
                otherHandle = profile?.handle.orEmpty(),
                otherName = profile?.displayName.orEmpty(),
                note = dto.note.orEmpty(),
                status = dto.status,
                outgoing = outgoing,
                createdAt = parseInstant(dto.createdAt),
            )
        }
        return Outcome.Success(FriendsSnapshot(FriendsAvailability.Ready, requests))
    }

    /**
     * Envoie une demande. Une ancienne demande refusée est effacée d'abord : sans cela, la
     * contrainte d'unicité (from_id, to_id) rendrait toute nouvelle demande impossible.
     * Une amitié acceptée n'est jamais effacée par cette étape.
     */
    suspend fun send(toId: String, note: String = ""): Outcome<Unit> {
        if (toId.isBlank()) {
            return Outcome.Failure(AppError.Validation("friend_target", "Choisissez une personne à ajouter."))
        }
        val me = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())
        if (me == toId) {
            return Outcome.Failure(AppError.Validation("friend_self", "Vous êtes déjà vous-même."))
        }
        val cleaned = note.trim().take(280)
        val id = UUID.randomUUID().toString()
        val payload = SupabaseApi.json.encodeToString(
            FriendRequestDto.serializer(),
            FriendRequestDto(id = id, fromId = me, toId = toId, note = cleaned, status = "Pending"),
        )
        return when (
            val inserted = api.insert(
                table = "friend_requests",
                body = payload,
                ignoreDuplicates = true,
                onConflict = "from_id,to_id",
                representation = false,
            )
        ) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> inserted
        }
    }

    /** Réponse du destinataire. Le serveur refuse cette écriture à tout autre compte (RLS). */
    suspend fun respond(id: String, accept: Boolean): Outcome<Unit> {
        val me = session.currentUserId ?: return Outcome.Failure(AppError.Unauthorized())
        val status = if (accept) "Accepted" else "Refused"
        val body = """{"status":"$status","responded_at":"${java.time.Instant.now()}"}"""
        return when (
            val updated = api.update(
                table = "friend_requests",
                filter = listOf("id" to "eq.$id", "to_id" to "eq.$me"),
                body = body,
            )
        ) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> updated
        }
    }

    /** Annule une demande envoyée, ou retire un ami. */
    suspend fun cancel(id: String): Outcome<Unit> {
        val me = session.currentUserId ?: return Outcome.Failure(AppError.Unauthorized())
        return when (
            val deleted = api.delete(
                table = "friend_requests",
                filter = listOf("id" to "eq.$id", "or" to "(from_id.eq.$me,to_id.eq.$me)"),
            )
        ) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> deleted
        }
    }

    private suspend fun profilesByIds(ids: Set<String>): Map<String, ProfileDto> {
        if (ids.isEmpty()) return emptyMap()
        val response = api.select(
            table = "profiles",
            query = listOf(
                "select" to "id,handle,display_name",
                "id" to "in.(${ids.joinToString(",")})",
            ),
            single = false,
        )
        val raw = (response as? Outcome.Success)?.value ?: return emptyMap()
        return runCatching {
            SupabaseApi.json.decodeFromString(ListSerializer(ProfileDto.serializer()), raw)
                .associateBy { it.id }
        }.getOrDefault(emptyMap())
    }

    private fun isMissingTable(error: AppError): Boolean {
        val text = error.technicalMessage + " " + error.userMessage
        return text.contains("PGRST205") ||
            text.contains("does not exist", ignoreCase = true) ||
            text.contains("schema cache", ignoreCase = true) ||
            text.contains("n'existe pas", ignoreCase = true)
    }

    private fun parseInstant(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    }
}
