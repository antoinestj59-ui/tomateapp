package tomate.data.backend

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** A persisted Supabase session plus the public identity derived from it. */
data class StoredSession(
    val userId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,
    val isAnonymous: Boolean,
)

interface SessionStore {
    suspend fun load(): StoredSession?
    suspend fun save(session: StoredSession)
    suspend fun clear()

    suspend fun putRecoveryCode(encrypted: String?)
    suspend fun recoveryCode(): String?

    suspend fun putHandle(handle: String?)
    suspend fun handle(): String?
}

private val Context.tomateDataStore: DataStore<Preferences> by preferencesDataStore(name = "tomate_session")

/**
 * Session material is bearer credentials: the refresh token is encrypted with the Android
 * Keystore ([tomate.data.security.KeystoreBox]) before being written to DataStore.
 */
class DataStoreSessionStore(
    private val context: Context,
    private val crypto: tomate.data.security.CryptoBox,
) : SessionStore {

    private object Keys {
        val userId = stringPreferencesKey("user_id")
        val accessToken = stringPreferencesKey("access_token")
        val refreshToken = stringPreferencesKey("refresh_token_enc")
        val expiresAt = stringPreferencesKey("expires_at")
        val isAnonymous = stringPreferencesKey("is_anonymous")
        val recoveryCode = stringPreferencesKey("recovery_code_enc")
        val handle = stringPreferencesKey("handle")
    }

    override suspend fun load(): StoredSession? {
        val prefs = context.tomateDataStore.data.first()
        val userId = prefs[Keys.userId] ?: return null
        val access = prefs[Keys.accessToken] ?: return null
        val refresh = prefs[Keys.refreshToken]?.let { crypto.decryptOrNull(it) }
        return StoredSession(
            userId = userId,
            accessToken = access,
            refreshToken = refresh,
            expiresAt = prefs[Keys.expiresAt]?.toLongOrNull() ?: 0L,
            isAnonymous = prefs[Keys.isAnonymous]?.toBoolean() ?: true,
        )
    }

    override suspend fun save(session: StoredSession) {
        context.tomateDataStore.edit { prefs ->
            prefs[Keys.userId] = session.userId
            prefs[Keys.accessToken] = session.accessToken
            prefs[Keys.expiresAt] = session.expiresAt.toString()
            prefs[Keys.isAnonymous] = session.isAnonymous.toString()
            val encryptedRefresh = session.refreshToken?.let { crypto.encrypt(it) }
            if (encryptedRefresh != null) prefs[Keys.refreshToken] = encryptedRefresh
        }
    }

    override suspend fun clear() {
        context.tomateDataStore.edit { prefs ->
            prefs.remove(Keys.userId)
            prefs.remove(Keys.accessToken)
            prefs.remove(Keys.refreshToken)
            prefs.remove(Keys.expiresAt)
            prefs.remove(Keys.isAnonymous)
            prefs.remove(Keys.handle)
        }
    }

    override suspend fun putRecoveryCode(encrypted: String?) {
        context.tomateDataStore.edit { prefs ->
            if (encrypted == null) prefs.remove(Keys.recoveryCode) else prefs[Keys.recoveryCode] = encrypted
        }
    }

    override suspend fun recoveryCode(): String? =
        context.tomateDataStore.data.first()[Keys.recoveryCode]

    override suspend fun putHandle(handle: String?) {
        context.tomateDataStore.edit { prefs ->
            if (handle == null) prefs.remove(Keys.handle) else prefs[Keys.handle] = handle
        }
    }

    override suspend fun handle(): String? = context.tomateDataStore.data.first()[Keys.handle]
}

/** In-memory implementation used by unit tests and previews. */
class InMemorySessionStore(
    private var session: StoredSession? = null,
    private var recovery: String? = null,
    private var handle: String? = null,
) : SessionStore {
    override suspend fun load(): StoredSession? = session
    override suspend fun save(session: StoredSession) {
        this.session = session
    }

    override suspend fun clear() {
        session = null
        handle = null
    }

    override suspend fun putRecoveryCode(encrypted: String?) {
        recovery = encrypted
    }

    override suspend fun recoveryCode(): String? = recovery
    override suspend fun putHandle(handle: String?) {
        this.handle = handle
    }

    override suspend fun handle(): String? = handle
}
