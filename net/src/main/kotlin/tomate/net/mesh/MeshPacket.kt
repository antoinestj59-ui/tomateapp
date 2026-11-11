package tomate.net.mesh

import java.security.MessageDigest
import java.util.Base64

/**
 * Paquet du maillage TOMATE, inspiré de bitchat (voir `docs/08_RESEAU_ET_CHIFFREMENT.md`) :
 *
 *  - un **en-tête lisible** (type, origine, TTL, horodatage) que les relais doivent comprendre ;
 *  - un **contenu opaque** (base64) que les relais ne lisent jamais : c'est du texte déjà chiffré
 *    de bout en bout quand la conversation est 1-1 ;
 *  - une **signature** facultative de l'origine, vérifiée avant tout relais.
 *
 * Différences assumées avec bitchat : pas de Noise (TOMTE utilise son propre ECIES P-256 décrit
 * dans `core/crypto/E2ee.kt`), pas de fanout déterministe, pas de fragments propres à ce paquet
 * (la fragmentation existante de `EnvelopeCodec` reste utilisée pour le transport).
 */
enum class MeshType(val code: Char) {
    /** Présence et clés publiques. */
    ANNOUNCE('A'),

    /** Message de diffusion. */
    MESSAGE('M'),

    /** Message privé confié à un relais (store-and-forward). */
    COURIER('C'),

    /** Demande de synchronisation, locale au lien. */
    REQUEST_SYNC('S'),

    /** Accusé de remise. */
    ACK('K'),
    ;

    companion object {
        fun from(code: Char): MeshType? = entries.firstOrNull { it.code == code }
    }
}

data class MeshPacket(
    val id: String,
    val type: MeshType,
    val origin: String,
    val destination: String?,
    val ttl: Int,
    val createdAt: Long,
    val payload: String,
    val signature: String? = null,
) {
    fun encode(): String {
        val encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        return listOf(
            PREFIX,
            id,
            type.code.toString(),
            origin,
            destination ?: "-",
            ttl.toString(),
            createdAt.toString(),
            signature ?: "-",
            encodedPayload,
        ).joinToString("|")
    }

    /** Le paquet tel qu'il est signé : tout sauf la signature elle-même. */
    fun signedBytes(): ByteArray = encodeWithoutSignature().toByteArray(Charsets.UTF_8)

    fun encodeWithoutSignature(): String = listOf(
        PREFIX,
        id,
        type.code.toString(),
        origin,
        destination ?: "-",
        ttl.toString(),
        createdAt.toString(),
        "-",
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8)),
    ).joinToString("|")

    fun decremented(): MeshPacket = copy(ttl = ttl - 1)

    companion object {
        const val PREFIX = "T2"
        const val MAX_TTL = 7
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

        /** Identifiant de paquet : dérivé du contenu, donc stable entre deux relais. */
        fun idFor(origin: String, createdAt: Long, type: MeshType, payload: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$origin|$createdAt|${type.code}|$payload".toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(8))
        }

        fun decode(text: String): MeshPacket? {
            val parts = text.split('|')
            if (parts.size != 9 || parts[0] != PREFIX) return null
            val type = MeshType.from(parts[2].singleOrNull() ?: return null) ?: return null
            val ttl = parts[5].toIntOrNull() ?: return null
            if (ttl !in 0..MAX_TTL) return null
            val createdAt = parts[6].toLongOrNull() ?: return null
            val payload = runCatching {
                String(Base64.getUrlDecoder().decode(parts[8]), Charsets.UTF_8)
            }.getOrNull() ?: return null
            if (payload.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return null
            val id = parts[1]
            val origin = parts[3]
            val destination = parts[4]
            if (id.isBlank() || origin.isBlank()) return null
            if (payload.isBlank()) return null
            if (destination.isBlank() || (destination != "-" && destination.length > 32)) return null
            return MeshPacket(
                id = id,
                type = type,
                origin = origin,
                destination = destination.takeIf { it != "-" },
                ttl = ttl,
                createdAt = createdAt,
                payload = payload,
                signature = parts[7].takeIf { it != "-" },
            )
        }

        /** Tag de destinataire **rotatif** : change chaque jour, pour qu'un relais ne puisse pas
         *  suivre une même personne sur la durée (mécanisme repris de bitchat). */
        fun recipientTag(userId: String, epochDay: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("tomate-tag|$userId|$epochDay".toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(6))
        }
    }
}
