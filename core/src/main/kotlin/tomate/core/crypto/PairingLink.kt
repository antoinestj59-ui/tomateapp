package tomate.core.crypto

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Appairage par lien, QR code ou NFC : un seul format, `tomate://pair`, qui transporte
 * l'identité **et les clés publiques** de chiffrement. C'est le même lien qui est
 * affiché en QR, écrit sur une étiquette NFC et collé à la main — un seul code à maintenir.
 *
 * Sans clés, l'appairage ne serait qu'un ajout de contact : le premier message devrait alors
 * demander les clés au serveur, et un serveur malveillant pourrait substituer les siennes.
 * En les faisant circuler hors bande (QR, NFC, lien), l'empreinte affichée après l'appairage a
 * une valeur réelle.
 */
data class PairingPayload(
    val userId: String,
    val handle: String,
    val agreeKey: String,
    val signKey: String,
    val version: Int = VERSION,
) {
    val fingerprint: String? get() = EcKeys.fingerprint(signKey)

    fun toLink(): String = PairingLink.encode(this)

    companion object {
        const val VERSION = 1
    }
}

object PairingLink {

    const val SCHEME = "tomate"
    const val HOST = "pair"
    private const val PREFIX = "$SCHEME://$HOST?"

    private val userIdPattern =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val handlePattern = Regex("^[a-z0-9_]{3,20}$")

    fun encode(payload: PairingPayload): String {
        require(userIdPattern.matches(payload.userId)) { "Identifiant invalide." }
        require(handlePattern.matches(payload.handle)) { "Pseudo invalide." }
        require(EcKeys.decodePublic(payload.agreeKey) != null) { "Clé d'accord invalide." }
        require(EcKeys.decodePublic(payload.signKey) != null) { "Clé de signature invalide." }
        val query = listOf(
            "v" to payload.version.toString(),
            "u" to payload.userId,
            "n" to payload.handle,
            "a" to payload.agreeKey,
            "s" to payload.signKey,
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        return PREFIX + query
    }

    /**
     * Accepte un lien complet, un lien sans schéma (`pair?...`) ou une simple chaîne de
     * paramètres — c'est ce qui rend le collage à la main tolérant sans accepter n'importe quoi.
     */
    fun parse(raw: String): PairingPayload? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 512) return null
        val query = when {
            text.startsWith(PREFIX, ignoreCase = true) -> text.substring(PREFIX.length)
            text.startsWith("$SCHEME://$HOST", ignoreCase = true) -> {
                val index = text.indexOf('?')
                if (index < 0) "" else text.substring(index + 1)
            }
            text.startsWith("$HOST?") -> text.substring("$HOST?".length)
            text.contains('=') && !text.contains("://") -> text
            else -> return null
        }
        if (query.isBlank()) return null
        val params = query.split('&').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) null else part.substring(0, separator) to decode(part.substring(separator + 1))
        }.toMap()
        val version = params["v"]?.toIntOrNull() ?: PairingPayload.VERSION
        if (version != PairingPayload.VERSION) return null
        val userId = params["u"] ?: return null
        val handle = params["n"] ?: return null
        val agreeKey = params["a"] ?: return null
        val signKey = params["s"] ?: return null
        if (!userIdPattern.matches(userId) || !handlePattern.matches(handle)) return null
        if (EcKeys.decodePublic(agreeKey) == null || EcKeys.decodePublic(signKey) == null) return null
        return PairingPayload(userId = userId, handle = handle, agreeKey = agreeKey, signKey = signKey, version = version)
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
