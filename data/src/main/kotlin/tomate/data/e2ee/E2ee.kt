package tomate.data.e2ee

/**
 * End-to-end encryption boundary.
 *
 * HONEST STATUS: E2EE is **not operational** in this version. The only implementation shipped
 * is [E2eeNotConfigured], which reports the truth to the UI so that messages are clearly labelled
 * as not end-to-end encrypted. No custom cryptography exists anywhere in TOMATE.
 *
 * Candidate protocols, evaluated in docs/04_SECURITE.md §5:
 *  - libsignal-android 0.76.1 (org.signal:libsignal-android, available on Maven Central) —
 *    the real Signal protocol (X3DH + Double Ratchet). Requires prekey publication, session
 *    store, safety-number UI and a server component; a partial integration would be worse than
 *    none, so it is planned as a dedicated milestone, not faked.
 *  - MLS (RFC 9420) for groups — no maintained Kotlin/Android implementation verified yet.
 */
interface E2eeEngine {
    val status: E2eeStatus

    /** Encrypts a message for one conversation. Must throw if E2EE is not operational. */
    suspend fun encrypt(conversationId: String, plaintext: String): E2eeResult

    suspend fun decrypt(conversationId: String, ciphertext: String): E2eeResult

    /** Publish this device's identity/prekeys to the backend. */
    suspend fun publishIdentityKeys(): E2eeResult

    sealed interface E2eeResult {
        data class Ok(val payload: String) : E2eeResult
        data class Failed(val reason: String) : E2eeResult
    }
}

enum class E2eeStatus {
    /** No E2EE: content is stored and transmitted unencrypted (transport security only). */
    NotConfigured,

    /** Keys published, sessions established, audited: safe to label messages as E2EE. */
    Operational,
}

/**
 * The shipped implementation. It refuses to pretend: [encrypt] and [decrypt] fail explicitly.
 */
class E2eeNotConfigured : E2eeEngine {
    override val status: E2eeStatus = E2eeStatus.NotConfigured

    override suspend fun encrypt(conversationId: String, plaintext: String): E2eeEngine.E2eeResult =
        E2eeEngine.E2eeResult.Failed(
            "Le chiffrement de bout en bout n'est pas encore opérationnel : ce message n'est pas chiffré de bout en bout.",
        )

    override suspend fun decrypt(conversationId: String, ciphertext: String): E2eeEngine.E2eeResult =
        E2eeEngine.E2eeResult.Failed("Le chiffrement de bout en bout n'est pas opérationnel.")

    override suspend fun publishIdentityKeys(): E2eeEngine.E2eeResult =
        E2eeEngine.E2eeResult.Failed("Aucune clé E2EE n'est publiée dans cette version.")
}

/** User-facing label used everywhere a message is displayed. */
fun E2eeStatus.badgeLabel(): String = when (this) {
    E2eeStatus.NotConfigured -> "Non chiffré de bout en bout"
    E2eeStatus.Operational -> "Chiffré de bout en bout"
}
