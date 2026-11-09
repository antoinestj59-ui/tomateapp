package tomate.data.container

import android.content.Context
import tomate.core.model.Outcome
import tomate.data.backend.DataStoreSessionStore
import tomate.data.backend.HttpClientFactory
import tomate.data.backend.SessionManager
import tomate.data.backend.SessionStore
import tomate.data.backend.SupabaseApi
import tomate.data.backend.SupabaseConfig
import tomate.data.db.TomateDatabase
import tomate.data.e2ee.E2eeEngine
import tomate.data.e2ee.E2eeNotConfigured
import tomate.data.network.NetworkMonitor
import tomate.data.repo.CommunityRepository
import tomate.data.repo.FeedRepository
import tomate.data.repo.MessagingRepository
import tomate.data.repo.ModerationRepository
import tomate.data.repo.ProfileRepository
import tomate.data.repo.StoryRepository
import tomate.data.security.CryptoBox
import tomate.data.security.KeystoreCryptoBox
import tomate.data.sync.SyncEngine

/**
 * Manual dependency container. TOMATE deliberately avoids a DI framework: the graph is small,
 * explicit and inspectable, which matters for a security-sensitive app.
 */
class TomateContainer(
    context: Context,
    crypto: CryptoBox = KeystoreCryptoBox(),
    sessionStoreFactory: (Context, CryptoBox) -> SessionStore = { ctx, box -> DataStoreSessionStore(ctx, box) },
) {
    private val appContext = context.applicationContext

    val database: TomateDatabase by lazy { TomateDatabase.build(appContext) }
    val crypto: CryptoBox = crypto
    val sessionStore: SessionStore = sessionStoreFactory(appContext, crypto)

    val http by lazy { HttpClientFactory.create() }
    val api: SupabaseApi by lazy {
        SupabaseApi(SupabaseConfig.url, SupabaseConfig.publishableKey, http)
    }
    val session: SessionManager by lazy { SessionManager(api, sessionStore) }
    val e2ee: E2eeEngine = E2eeNotConfigured()
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }
    val settings: tomate.data.settings.SettingsStore by lazy {
        tomate.data.settings.SettingsStore(appContext)
    }

    val profiles: ProfileRepository by lazy {
        ProfileRepository(api, database.profileDao(), session)
    }
    val feed: FeedRepository by lazy {
        FeedRepository(
            api = api,
            postDao = database.postDao(),
            commentDao = database.commentDao(),
            profileDao = database.profileDao(),
            outboxDao = database.outboxDao(),
            session = session,
        )
    }
    val stories: StoryRepository by lazy {
        StoryRepository(
            api = api,
            storyDao = database.storyDao(),
            profileDao = database.profileDao(),
            outboxDao = database.outboxDao(),
            session = session,
        )
    }
    val communities: CommunityRepository by lazy {
        CommunityRepository(api, database.communityDao(), database.outboxDao(), session)
    }
    val messaging: MessagingRepository by lazy {
        MessagingRepository(
            api = api,
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            profileDao = database.profileDao(),
            outboxDao = database.outboxDao(),
            session = session,
            e2ee = e2ee,
        )
    }
    val moderation: ModerationRepository by lazy {
        ModerationRepository(api, database.blockDao(), database.outboxDao(), session)
    }
    val sync: SyncEngine by lazy {
        SyncEngine(
            api = api,
            outboxDao = database.outboxDao(),
            canSync = { !session.isLocalAccount() },
        )
    }

    /** Migrates a local (offline) account to Supabase once the network is back. */
    val account: tomate.data.account.AccountLinker by lazy {
        tomate.data.account.AccountLinker(
            api = api,
            session = session,
            profileDao = database.profileDao(),
            postDao = database.postDao(),
            commentDao = database.commentDao(),
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            communityDao = database.communityDao(),
            outboxDao = database.outboxDao(),
        )
    }

    /**
     * First-launch bootstrap: ensure an anonymous session exists and refresh cached data.
     * Returns the session so the caller can decide what to show when the network is down.
     */
    suspend fun bootstrap(): Outcome<String> {
        // ensureActor never fails because of connectivity: it falls back to a local identity.
        val sessionResult = session.ensureActor()
        return when (sessionResult) {
            is Outcome.Success -> {
                runCatching { profiles.pullMyProfile() }
                Outcome.Success(sessionResult.value.userId)
            }
            is Outcome.Failure -> sessionStore.load()?.let { Outcome.Success(it.userId) }
                ?: Outcome.Failure(sessionResult.error)
        }
    }
}
