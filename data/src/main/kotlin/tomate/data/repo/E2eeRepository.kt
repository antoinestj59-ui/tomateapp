package tomate.data.repo

import kotlinx.serialization.builtins.ListSerializer
import tomate.core.model.Outcome
import tomate.data.backend.SessionManager
import tomate.data.backend.SupabaseApi
import tomate.data.backend.dto.ProfileDto
import tomate.data.e2ee.E2eeDirectory
import tomate.data.e2ee.E2eeIdentity
import tomate.data.e2ee.E2eeStore
import tomate.data.e2ee.PeerKeySource
import tomate.data.e2ee.PeerPublicKeys

/**
 * Annuaire des clés publiques E2EE, adossé à `profiles.e2ee_agree_key` / `e2ee_sign_key`
 * (migration 0011).
 *
 * Deux garanties :
 *  - on ne publie que **ses** clés publiques (le serveur refuse toute autre ligne via RLS) ;
 *  - une clé reçue par appairage reste prioritaire : [savePeer] local ne l'écrase jamais, et la
 *    lecture de l'annuaire ne remplace pas une clé épinglée.
 */
class E2eeRepository(
    private val api: SupabaseApi,
    private val session: SessionManager,
    private val store: E2eeStore,
) : E2eeDirectory {

    override suspend fun fetch(userId: String): PeerPublicKeys? {
        if (session.isLocalAccount()) return null
        val query = listOf(
            "select" to "id,handle,e2ee_agree_key,e2ee_sign_key",
            "id" to "eq.$userId",
            "limit" to "1",
        )
        return when (val response = api.select("profiles", query)) {
            is Outcome.Failure -> null
            is Outcome.Success -> {
                val profile = runCatching {
                    SupabaseApi.json.decodeFromString(ListSerializer(ProfileDto.serializer()), response.value)
                }.getOrNull()?.firstOrNull() ?: return null
                val agree = profile.e2eeAgreeKey
                val sign = profile.e2eeSignKey
                if (agree.isNullOrBlank() || sign.isNullOrBlank()) return null
                PeerPublicKeys(
                    userId = profile.id,
                    handle = profile.handle,
                    agreeKey = agree,
                    signKey = sign,
                    source = PeerKeySource.Directory,
                )
            }
        }
    }

    override suspend fun publish(identity: E2eeIdentity): Boolean {
        if (session.isLocalAccount()) return false
        val body = buildString {
            append("{\"e2ee_agree_key\":\"").append(identity.agreePublic).append("\",")
            append("\"e2ee_sign_key\":\"").append(identity.signPublic).append("\"}")
        }
        return when (api.update("profiles", listOf("id" to "eq.${identity.userId}"), body)) {
            is Outcome.Success -> true
            is Outcome.Failure -> false
        }
    }

    /**
     * Appairage : la clé est épinglée localement. Si une clé d'annuaire existait déjà, elle est
     * remplacée ; l'inverse n'est pas vrai (voir [E2eeDirectory]).
     */
    fun pin(peer: PeerPublicKeys) {
        store.savePeer(peer)
    }

    fun cachedPeer(userId: String): PeerPublicKeys? = store.peer(userId)

    fun knownPeers(): List<PeerPublicKeys> = store.peers()
}
