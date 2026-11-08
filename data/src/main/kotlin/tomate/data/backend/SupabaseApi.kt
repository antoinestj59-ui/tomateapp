package tomate.data.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tomate.core.model.AppError
import tomate.core.model.Outcome
import tomate.data.backend.dto.AuthSessionDto

/**
 * Minimal, auditable Supabase HTTP client.
 *
 * ADR-002 (docs/02_ARCHITECTURE.md): TOMATE talks to the documented Supabase endpoints
 * (auth, rest and storage prefixes under v1) through a thin Ktor layer instead of the
 * supabase-kt Kotlin client. Rationale: the full request/response contract stays explicit and
 * testable (Ktor MockEngine), there is no hidden serializer or API-surface churn, and the whole
 * client fits in one reviewable file. supabase-kt 3.9.0 was verified as maintained and aligned
 * with Kotlin 2.4.21 / Ktor 3.6.0; it remains a valid alternative if this layer grows too big.
 *
 * Security: only the publishable key is used. This class never sees a service_role key, and it
 * never logs tokens or request bodies.
 */
class SupabaseApi(
    private val baseUrl: String,
    private val apiKey: String,
    private val http: HttpClient,
) {
    /**
     * Supplies the current access token. `forceRefresh = true` is used after a 401: the manager
     * refreshes the session once and the request is replayed.
     */
    var tokenProvider: (suspend (forceRefresh: Boolean) -> String?)? = null

    private val refreshMutex = Mutex()

    fun endpoint(path: String): String = baseUrl.trimEnd('/') + path

    // ---------------------------------------------------------------- Auth

    suspend fun signInAnonymously(): Outcome<RemoteSession> = runCatchingOutcome {
        val response = http.post(endpoint("/auth/v1/signup")) {
            header("apikey", apiKey)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        parseSession(response)
    }

    suspend fun refreshSession(refreshToken: String): Outcome<RemoteSession> = runCatchingOutcome {
        refreshMutex.withLock {
            val response = http.post(endpoint("/auth/v1/token")) {
                parameter("grant_type", "refresh_token")
                header("apikey", apiKey)
                contentType(ContentType.Application.Json)
                setBody("""{"refresh_token":"${refreshToken.replace("\"", "")}"}""")
            }
            parseSession(response)
        }
    }

    suspend fun signOut(accessToken: String?): Outcome<Unit> = runCatchingOutcome {
        if (accessToken != null) {
            http.post(endpoint("/auth/v1/logout")) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $accessToken")
            }
        }
        Unit
    }

    private suspend fun parseSession(response: HttpResponse): RemoteSession {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw mapStatusToException(response.status, text)
        val dto = json.decodeFromString(AuthSessionDto.serializer(), text)
        return RemoteSession(
            userId = dto.user.id,
            accessToken = dto.accessToken,
            refreshToken = dto.refreshToken,
            expiresAt = System.currentTimeMillis() + dto.expiresIn * 1000L,
            isAnonymous = dto.user.isAnonymous,
        )
    }

    // ------------------------------------------------------------ PostgREST

    suspend fun select(
        table: String,
        query: List<Pair<String, String>> = emptyList(),
        single: Boolean = false,
    ): Outcome<String> = authorized { token ->
        val response = http.get(endpoint("/rest/v1/$table")) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $token")
            header("Accept", "application/json")
            if (single) header("Accept", "application/vnd.pgrst.object+json")
            query.forEach { (key, value) -> parameter(key, value) }
        }
        readOrThrow(response)
    }

    suspend fun insert(
        table: String,
        body: String,
        upsert: Boolean = false,
        onConflict: String? = null,
    ): Outcome<String> = authorized { token ->
        val response = http.post(endpoint("/rest/v1/$table")) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $token")
            header("Prefer", buildString {
                append("return=representation")
                if (upsert) append(",resolution=merge-duplicates")
            })
            if (onConflict != null) parameter("on_conflict", onConflict)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        readOrThrow(response)
    }

    suspend fun update(
        table: String,
        filter: List<Pair<String, String>>,
        body: String,
    ): Outcome<String> = authorized { token ->
        val response = http.patch(endpoint("/rest/v1/$table")) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $token")
            header("Prefer", "return=representation")
            filter.forEach { (key, value) -> parameter(key, value) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        readOrThrow(response)
    }

    suspend fun delete(table: String, filter: List<Pair<String, String>>): Outcome<String> =
        authorized { token ->
            val response = http.delete(endpoint("/rest/v1/$table")) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $token")
                header("Prefer", "return=minimal")
                filter.forEach { (key, value) -> parameter(key, value) }
            }
            readOrThrow(response)
        }

    suspend fun rpc(function: String, body: String = "{}"): Outcome<String> = authorized { token ->
        val response = http.post(endpoint("/rest/v1/rpc/$function")) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        readOrThrow(response)
    }

    // -------------------------------------------------------------- Storage

    suspend fun uploadObject(
        bucket: String,
        path: String,
        bytes: ByteArray,
        contentType: String,
    ): Outcome<String> = authorized { token ->
        val response = http.post(endpoint("/storage/v1/object/$bucket/$path")) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $token")
            header("x-upsert", "false")
            header("Content-Type", contentType)
            setBody(bytes)
        }
        readOrThrow(response)
    }

    // -------------------------------------------------------------- helpers

    private suspend fun readOrThrow(response: HttpResponse): String {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw mapStatusToException(response.status, text)
        return text
    }

    /**
     * Runs an authenticated request and replays it once after refreshing the session on 401.
     * Never retries more than once, so a broken session cannot loop.
     */
    private suspend fun authorized(block: suspend (token: String) -> String): Outcome<String> {
        val provider = tokenProvider
            ?: return Outcome.Failure(AppError.Unauthorized("Aucune session configurée"))

        return try {
            val token = provider(false)
                ?: return Outcome.Failure(AppError.Unauthorized("Session inexistante"))
            try {
                Outcome.Success(block(token))
            } catch (e: SessionExpiredException) {
                val refreshed = provider(true)
                    ?: return Outcome.Failure(AppError.Unauthorized("Session expirée"))
                Outcome.Success(block(refreshed))
            }
        } catch (t: Throwable) {
            Outcome.Failure(mapThrowable(t))
        }
    }

    private fun mapStatusToException(status: HttpStatusCode, text: String): Throwable = when {
        status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
            SessionExpiredException(text)
        status == HttpStatusCode.TooManyRequests -> RequestFailedException(AppError.RateLimited(text))
        status == HttpStatusCode.NotFound -> {
            if (text.contains("PGRST205") || text.contains("42P01") || text.contains("schema cache")) {
                RequestFailedException(AppError.SchemaMissing(text))
            } else {
                RequestFailedException(AppError.NotFound(text))
            }
        }
        status == HttpStatusCode.Conflict || text.contains("23505") ->
            RequestFailedException(AppError.Conflict(text))
        status == HttpStatusCode.BadRequest || text.contains("\"code\":\"22") ->
            RequestFailedException(AppError.Validation(technicalMessage = text, userMessage = "Données invalides."))
        status.value >= 500 -> RequestFailedException(AppError.Server(text))
        else -> RequestFailedException(AppError.Unknown("HTTP ${status.value}: ${text.take(200)}"))
    }

    private fun mapThrowable(t: Throwable): AppError = when (t) {
        is RequestFailedException -> t.error
        is SessionExpiredException -> AppError.Unauthorized(t.message ?: "Session expirée")
        is java.io.IOException -> AppError.Offline(t.message ?: "Réseau indisponible")
        else -> {
            val message = t.message ?: t::class.java.simpleName
            when {
                message.contains("Unable to resolve host", true) ||
                    message.contains("timeout", true) ||
                    message.contains("ConnectException", true) ||
                    message.contains("UnknownHost", true) -> AppError.Offline(message)
                else -> AppError.Unknown(message)
            }
        }
    }

    private inline fun <T> runCatchingOutcome(block: () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (t: Throwable) {
        Outcome.Failure(mapThrowable(t))
    }

    companion object {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

data class RemoteSession(
    val userId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,
    val isAnonymous: Boolean,
)

class SessionExpiredException(message: String) : Exception(message)

class RequestFailedException(val error: AppError) : Exception(error.technicalMessage)
