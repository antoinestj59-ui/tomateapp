package tomate.data.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tomate.core.model.Outcome

/**
 * Offline account creation: with no reachable network, TOMATE must still give the user a real,
 * persisted identity instead of failing onboarding. Verified against a Ktor MockEngine.
 */
class OfflineIdentityTest {

    private var online = false
    private val remoteId = "11111111-1111-1111-1111-111111111111"

    private fun api(): SupabaseApi {
        val engine = MockEngine {
            if (!online) throw IOException("offline")
            respond(
                content = """{"access_token":"jwt","refresh_token":"refresh","expires_in":3600,"user":{"id":"$remoteId","is_anonymous":true}}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        return SupabaseApi("https://example.invalid", "publishable", HttpClient(engine))
    }

    @Test
    fun offlineCreationProducesPersistedLocalIdentity() = runBlocking {
        val store = InMemorySessionStore()
        val session = SessionManager(api(), store)

        val id = session.ensureActor().getOrNull()?.userId

        assertTrue("une identité locale est créée hors ligne", id != null && SessionManager.isLocalId(id!!))
        assertTrue(session.isLocalAccount())
        assertNull("aucun jeton réseau pour un compte local", session.accessToken())

        // A relaunch must keep the same offline identity (the user's account survives restarts).
        val relaunched = SessionManager(api(), store)
        assertEquals(id, relaunched.ensureActor().getOrNull()?.userId)
    }

    @Test
    fun onlineCreationProducesRemoteAccount() = runBlocking {
        online = true
        val session = SessionManager(api(), InMemorySessionStore())

        val actor = session.ensureActor()

        assertTrue(actor is Outcome.Success)
        assertEquals(remoteId, actor.getOrNull()?.userId)
        assertFalse(session.isLocalAccount())
        assertEquals("jwt", session.accessToken())
    }

    @Test
    fun explicitOfflineStartDoesNotTouchTheNetwork() = runBlocking {
        val session = SessionManager(api(), InMemorySessionStore())

        val local = session.startLocalSession()

        assertTrue(SessionManager.isLocalId(local.userId))
        assertEquals(local.userId, session.ensureActor().getOrNull()?.userId)
    }

    @Test
    fun localPrefixIsRecognised() {
        assertTrue(SessionManager.isLocalId("local-abc"))
        assertFalse(SessionManager.isLocalId("11111111-1111-1111-1111-111111111111"))
    }
}
