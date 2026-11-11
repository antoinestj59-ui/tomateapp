package tomate.data.e2ee

import tomate.core.crypto.E2ee

/**
 * Frontière du chiffrement de bout en bout.
 *
 * État réel de cette version (0.5.0) : les conversations **1-1** sont chiffrées côté client avec
 * P-256 + HKDF-SHA256 + AES-256-GCM et signées en ECDSA, exactement comme le fait la PWA
 * (`web/js/e2ee.js`, vérifié par `E2eeInteropTest`). Les **groupes ne sont pas chiffrés**, et il
 * n'y a pas de Double Ratchet : l'interface le dit, elle ne prétend rien de plus.
 */
interface E2eeEngine {

    val status: E2eeStatus

    /**
     * Prépare un message sortant.
     *
     * @param peerId identifiant de l'autre participant pour une conversation 1-1, `null` pour un
     *   groupe (qui n'est pas chiffré) : dans ce cas le texte part en clair et [Cipher.Ok.encrypted]
     *   vaut `false`, ce qui permet à l'interface de rester honnête.
     */
    suspend fun prepareOutgoing(
        conversationId: String,
        senderId: String,
        peerId: String?,
        plaintext: String,
    ): Cipher

    /** Ouvre un message reçu : déchiffre si nécessaire, rend le texte affichable. */
    suspend fun openIncoming(
        conversationId: String,
        senderId: String,
        body: String,
    ): Cipher

    /** Publie les clés publiques de cet appareil (annuaire), sans jamais envoyer les privées. */
    suspend fun publishIdentityKeys(): E2eeResult

    sealed interface Cipher {
        /** [text] est affichable (reçu) ou envoyable (sortant). */
        data class Ok(val text: String, val encrypted: Boolean) : Cipher

        /** Le message est chiffré mais ne peut pas être ouvert : on l'affiche comme tel. */
        data class Failed(val reason: String) : Cipher
    }

    sealed interface E2eeResult {
        data class Ok(val payload: String) : E2eeResult
        data class Failed(val reason: String) : E2eeResult
    }
}

enum class E2eeStatus {
    /** Aucune identité : rien n'est chiffré, et l'interface le dit. */
    NotConfigured,

    /** Identité prête : les conversations 1-1 sont chiffrées de bout en bout. */
    Operational,
}

/** Libellé affiché partout où l'état du chiffrement est montré. */
fun E2eeStatus.badgeLabel(): String = when (this) {
    E2eeStatus.NotConfigured -> "Non chiffré de bout en bout"
    E2eeStatus.Operational -> "Chiffré de bout en bout (1-1)"
}

/**
 * Implémentation neutre conservée pour les tests et pour tout code construit sans identité :
 * elle refuse explicitement au lieu de laisser croire à un chiffrement.
 */
class E2eeNotConfigured : E2eeEngine {
    override val status: E2eeStatus = E2eeStatus.NotConfigured

    override suspend fun prepareOutgoing(
        conversationId: String,
        senderId: String,
        peerId: String?,
        plaintext: String,
    ): E2eeEngine.Cipher = E2eeEngine.Cipher.Ok(plaintext, encrypted = false)

    override suspend fun openIncoming(
        conversationId: String,
        senderId: String,
        body: String,
    ): E2eeEngine.Cipher =
        if (E2ee.isEncrypted(body)) {
            E2eeEngine.Cipher.Failed("Message chiffré : aucune clé de déchiffrement sur cet appareil.")
        } else {
            E2eeEngine.Cipher.Ok(body, encrypted = false)
        }

    override suspend fun publishIdentityKeys(): E2eeEngine.E2eeResult =
        E2eeEngine.E2eeResult.Failed("Aucune identité de chiffrement dans cette version.")
}
