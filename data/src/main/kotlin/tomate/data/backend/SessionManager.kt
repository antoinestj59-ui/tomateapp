package tomate.data.backend

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tomate.core.model.AppError
import tomate.core.model.Outcome

/**
 * Single owner of the Supabase session.
 *
 * Behaviour:
 *  - first launch: creates an anonymous account and persists the session;
 *  - relaunch: restores the persisted session, refreshing it when it is close to expiry;
 *  - offline: keeps the persisted session and reports [AppError.Offline] so the UI can stay
 *    usable in local-only mode instead of destroying local data.
 */
class SessionManager(
    private val api: SupabaseApi,
    private val store: SessionStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var cached: StoredSession? = null

    init {
        api.tokenProvider = { forceRefresh -> accessToken(forceRefresh) }
    }

    val currentUserId: String? get() = cached?.userId

    /**
     * Guarantees a usable session, creating an anonymous account when nothing is stored.
     */
    suspend fun ensureSession(): Outcome<StoredSession> = mutex.withLock {
        cached?.let { existing ->
            if (existing.expiresAt - REFRESH_MARGIN_MS > clock()) return Outcome.Success(existing)
        }
        val stored = store.load()
        if (stored != null) {
            if (stored.expiresAt - REFRESH_MARGIN_MS > clock()) {
                cached = stored
                return Outcome.Success(stored)
            }
            val refreshed = tryRefresh(stored)
            if (refreshed != null) {
                cached = refreshed
                return Outcome.Success(refreshed)
            }
            // Refresh failed but the local session may still be usable offline.
            cached = stored
            return Outcome.Failure(AppError.Offline("Session non rafraîchie"))
        }
        createAnonymousSession()
    }

    /** Returns an access token, refreshing it when needed. Null only if no session exists. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? {
        val current = cached ?: store.load()?.also { cached = it } ?: return null
        val stillValid = current.expiresAt - REFRESH_MARGIN_MS > clock()
        if (!forceRefresh && stillValid) return current.accessToken

        val refreshed = tryRefresh(current)
        if (refreshed != null) {
            cached = refreshed
            return refreshed.accessToken
        }
        return if (stillValid) current.accessToken else null
    }

    private suspend fun tryRefresh(session: StoredSession): StoredSession? {
        val refreshToken = session.refreshToken ?: return null
        return when (val result = api.refreshSession(refreshToken)) {
            is Outcome.Success -> {
                val remote = result.value
                val stored = StoredSession(
                    userId = remote.userId,
                    accessToken = remote.accessToken,
                    refreshToken = remote.refreshToken ?: session.refreshToken,
                    expiresAt = remote.expiresAt,
                    isAnonymous = remote.isAnonymous,
                )
                store.save(stored)
                stored
            }
            is Outcome.Failure -> null
        }
    }

    private suspend fun createAnonymousSession(): Outcome<StoredSession> =
        when (val result = api.signInAnonymously()) {
            is Outcome.Success -> {
                val remote = result.value
                val stored = StoredSession(
                    userId = remote.userId,
                    accessToken = remote.accessToken,
                    refreshToken = remote.refreshToken,
                    expiresAt = remote.expiresAt,
                    isAnonymous = remote.isAnonymous,
                )
                store.save(stored)
                cached = stored
                Outcome.Success(stored)
            }
            is Outcome.Failure -> Outcome.Failure(result.error)
        }

    suspend fun signOut(): Outcome<Unit> = mutex.withLock {
        val token = cached?.accessToken
        runCatching { api.signOut(token) }
        store.clear()
        cached = null
        Outcome.Success(Unit)
    }

    /** Restores a session from an already validated session object (device migration path). */
    suspend fun adopt(session: StoredSession) {
        store.save(session)
        cached = session
    }

    private companion object {
        const val REFRESH_MARGIN_MS = 60_000L
    }
}
