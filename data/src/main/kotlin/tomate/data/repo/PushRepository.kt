package tomate.data.repo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tomate.core.model.AppError
import tomate.core.model.Outcome
import tomate.data.backend.SessionManager
import tomate.data.backend.SupabaseApi

/**
 * Registers the Firebase Cloud Messaging token of this device for the signed-in account.
 *
 * The row lives in `device_tokens` (migration 0007) and is protected by RLS: a client can only
 * read or write its own tokens. Sending itself is done server-side by the `push` Edge Function,
 * which is the only component that holds the Firebase service account.
 */
class PushRepository(
    private val api: SupabaseApi,
    private val session: SessionManager,
) {
    suspend fun registerToken(token: String, platform: String = "android"): Outcome<Unit> {
        if (token.isBlank()) {
            return Outcome.Failure(AppError.Validation("Jeton de notification vide", "Notifications indisponibles sur cet appareil."))
        }
        val actor = session.ensureActor()
        if (actor is Outcome.Failure) return actor
        val userId = session.currentUserId
            ?: return Outcome.Failure(AppError.Unauthorized())
        val body = buildJsonObject {
            put("user_id", userId)
            put("token", token)
            put("platform", platform)
        }.toString()
        return when (
            val result = api.insert(
                table = "device_tokens",
                body = body,
                upsert = true,
                onConflict = "user_id,token",
                representation = false,
            )
        ) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> result
        }
    }

    suspend fun forgetToken(token: String): Outcome<Unit> = when (
        val result = api.delete("device_tokens", listOf("token" to "eq.$token"))
    ) {
        is Outcome.Success -> Outcome.Success(Unit)
        is Outcome.Failure -> result
    }
}
