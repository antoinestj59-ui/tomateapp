package tomate.net.mesh

import java.security.PublicKey
import kotlin.random.Random
import tomate.core.crypto.Ecdsa

/**
 * Politique de relais du maillage, volontairement stricte et testable sans radio.
 *
 * Reprise de ce qui fait fonctionner bitchat et Meshtastic :
 *  - **TTL décrémenté** à chaque relais, avec un plafond (pas de boucle infinie) ;
 *  - **déduplication** par identifiant de paquet, sur une fenêtre bornée (pas de tempête) ;
 *  - **split horizon** : on ne renvoie jamais un paquet au lien d'où il vient ;
 *  - **délai aléatoire** avant renvoi, pour que deux relais ne parlent pas en même temps ;
 *  - **signature vérifiée** avant tout relais : on ne transporte pas n'importe quoi.
 */
class MeshRouter(
    private val localTag: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val forwardingEnabled: () -> Boolean = { true },
    private val random: Random = Random.Default,
    private val maxDelayMs: Long = 250,
    private val verifier: ((MeshPacket) -> Boolean)? = null,
) {

    private val seen = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_SEEN
    }

    sealed interface Decision {
        /** Le paquet est pour cet appareil (ou une diffusion) : on le remonte à l'application. */
        data class Deliver(val packet: MeshPacket) : Decision

        /** Le paquet est pour quelqu'un d'autre : il repart après [delayMs], jamais vers [exceptLink]. */
        data class Forward(val packet: MeshPacket, val delayMs: Long, val exceptLink: String?) : Decision

        data class Drop(val reason: Reason) : Decision
    }

    enum class Reason {
        UnsupportedVersion,
        Malformed,
        InvalidSignature,
        Expired,
        TtlExhausted,
        Duplicate,
        ForwardingDisabled,
    }

    fun receive(packet: MeshPacket, incomingLink: String? = null): Decision {
        val now = clock()
        if (packet.ttl <= 0) return Decision.Drop(Reason.TtlExhausted)
        if (now - packet.createdAt > MeshPacket.MAX_AGE_MS) return Decision.Drop(Reason.Expired)
        if (verifier != null && !verifier(packet)) return Decision.Drop(Reason.InvalidSignature)

        val key = "${packet.origin}|${packet.id}"
        if (isDuplicate(key, now)) return Decision.Drop(Reason.Duplicate)

        val forMe = packet.destination == null || packet.destination == localTag
        if (forMe) return Decision.Deliver(packet)

        if (!forwardingEnabled()) return Decision.Drop(Reason.ForwardingDisabled)
        return Decision.Forward(
            packet = packet.decremented(),
            delayMs = if (maxDelayMs <= 0) 0 else random.nextLong(maxDelayMs + 1),
            exceptLink = incomingLink,
        )
    }

    private fun isDuplicate(key: String, now: Long): Boolean {
        synchronized(seen) {
            val previous = seen[key]
            if (previous != null && now - previous <= DEDUPE_WINDOW_MS) return true
            seen[key] = now
            return false
        }
    }

    /** Nombre d'identifiants mémorisés (borné) : exposé pour les tests et le diagnostic. */
    fun seenCount(): Int = synchronized(seen) { seen.size }

    companion object {
        /** Comme bitchat : 1000 identifiants sur cinq minutes. */
        const val MAX_SEEN = 1000
        const val DEDUPE_WINDOW_MS = 5L * 60L * 1000L

        /** Vérificateur prêt à l'emploi : signature ECDSA P-256 de l'origine. */
        fun verifierFor(publicKeyOf: (String) -> PublicKey?): (MeshPacket) -> Boolean = { packet ->
            val signature = packet.signature?.let { raw ->
                runCatching { java.util.Base64.getUrlDecoder().decode(raw) }.getOrNull()
            }
            val key = publicKeyOf(packet.origin)
            if (signature == null || key == null) {
                false
            } else {
                Ecdsa.verifyRaw(key, packet.signedBytes(), signature)
            }
        }
    }
}

/**
 * Courrier : messages privés confiés à un relais, bornés en nombre et en durée.
 *
 * Un relais ne détient que du contenu déjà chiffré, adressé par un tag rotatif. Quand la capacité
 * est atteinte, le plus ancien part : un relais ne devient jamais un dépôt permanent.
 */
class CourierStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val capacity: Int = 200,
    private val ttlMs: Long = MeshPacket.MAX_AGE_MS,
) {
    private val entries = LinkedHashMap<String, MeshPacket>()

    fun deposit(packet: MeshPacket): Boolean {
        purge()
        if (packet.destination == null) return false
        synchronized(entries) {
            entries[packet.id] = packet
            while (entries.size > capacity) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
        return true
    }

    fun due(): List<MeshPacket> {
        purge()
        return synchronized(entries) { entries.values.toList() }
    }

    fun forTag(tag: String): List<MeshPacket> = due().filter { it.destination == tag }

    fun acknowledge(packetId: String) {
        synchronized(entries) { entries.remove(packetId) }
    }

    fun purge() {
        val now = clock()
        synchronized(entries) {
            entries.entries.removeAll { (_, packet) -> now - packet.createdAt > ttlMs }
        }
    }

    fun size(): Int {
        purge()
        return synchronized(entries) { entries.size }
    }

    fun clear() = synchronized(entries) { entries.clear() }
}
