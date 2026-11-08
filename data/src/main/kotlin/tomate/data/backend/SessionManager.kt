package tomate.data.backend

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tomate.core.model.AppError
import tomate.core.model.Outcome

/**
 * Single owner of the identity used by the app.
 *
 * Two kinds of identity exist:
 *  - **remote**: a real Supabase anonymous account, with a refreshable JWT;
 *  - **local**: `local-<uuid>`, created on-device when no connection is available. A local
 *    account is a complete, usable account: everything is written to Room, queued in the outbox
 *    and migrated to Supabase by [tomate.data.account.AccountLinker] on the next connection.
 *
 * Behaviour:
 *  - first launch online: creates an anonymous account and persists the session;
 *  - first launch offline: creates a local identity, so onboarding never fails;
 *  - relaunch: restores the persisted session, refreshing it when it is close to expiry;
 *  - offline: keeps the persisted identity instead of destroying local data.
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

    /**
     * Returns an identity that always exists, even with no network at all.
     *
     * Order: live remote session, stored remote session that could not be refreshed, then a new
     * local identity. Account creation therefore never fails because of connectivity.
     */
    suspend fun ensureActor(): Outcome<StoredSession> = mutex.withLock {
        val current = cached ?: store.load()?.also { cached = it }
        if (current != null) {
            if (isLocalId(current.userId)) return Outcome.Success(current)
            if (current.expiresAt - REFRESH_MARGIN_MS > clock()) return Outcome.Success(current)
            val refreshed = tryRefresh(current)
            if (refreshed != null) {
                cached = refreshed
                return Outcome.Success(refreshed)
            }
            return Outcome.Success(current)
        }
        when (val created = createAnonymousSession()) {
            is Outcome.Success -> created
            is Outcome.Failure -> Outcome.Success(createLocalSession())
        }
    }

    /** Creates a local-only identity on purpose (explicit "create offline" action). */
    suspend fun startLocalSession(): StoredSession = mutex.withLock {
        cached?.takeIf { isLocalId(it.userId) }?.let { return it }
        val stored = store.load()
        if (stored != null && isLocalId(stored.userId)) {
            cached = stored
            return stored
        }
        createLocalSession()
    }

    /** True when the current identity has not been migrated to Supabase yet. */
    suspend fun isLocalAccount(): Boolean {
        val current = cached ?: store.load()?.also { cached = it }
        return current != null && isLocalId(current.userId)
    }

    /**
     * Drops the local identity and creates a real anonymous account. Only used by the linker;
     * the network error is returned untouched so the caller can retry later.
     */
    suspend fun ensureRemoteSession(): Outcome<StoredSession> = mutex.withLock {
        store.clear()
        cached = null
        createAnonymousSession()
    }

    private suspend fun createLocalSession(): StoredSession {
        val local = StoredSession(
            userId = LOCAL_PREFIX + UUID.randomUUID().toString(),
            accessToken = "",
            refreshToken = null,
            expiresAt = Long.MAX_VALUE / 2,
            isAnonymous = true,
        )
        store.save(local)
        cached = local
        return local
    }

    /** Returns an access token, refreshing it when needed. Null only if no session exists. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? {
        val current = cached ?: store.load()?.also { cached = it } ?: return null
        if (isLocalId(current.userId)) return null
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

    companion object {
        const val LOCAL_PREFIX = "local-"
        private const val REFRESH_MARGIN_MS = 60_000L

        /** Ids created on-device before any Supabase account existed. */
        fun isLocalId(userId: String): Boolean = userId.startsWith(LOCAL_PREFIX)
    }
}
