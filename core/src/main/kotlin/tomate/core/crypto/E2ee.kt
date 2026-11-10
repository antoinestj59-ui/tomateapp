package tomate.core.crypto

import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement de bout en bout 1-1 de TOMATE, sans dépendance externe et interopérable
 * Android <-> PWA.
 *
 * **Ce que c'est** : un chiffrement par message, à clé publique, de type ECIES — le message part
 * avec une clé éphémère, le destinataire le déchiffre avec sa clé privée d'identité, et une
 * signature ECDSA de l'émetteur garantit l'authenticité. Le serveur (Supabase) ne stocke que
 * `body` chiffré : il ne peut plus lire les messages 1-1.
 *
 * **Ce que ce n'est pas** — et qui reste affiché comme tel dans l'app :
 *  - pas de Double Ratchet : pas de confidentialité persistante côté destinataire (une clé
 *    d'identité volée permet de déchiffrer les anciens messages) ;
 *  - pas de multi-appareil (une clé par installation) ;
 *  - pas de chiffrement des groupes : seules les conversations 1-1 sont chiffrées ;
 *  - l'authenticité repose sur la clé publique reçue (appairage QR/lien ou annuaire), donc un
 *    échange de clés non vérifié hors bande reste vulnérable à un serveur malveillant. C'est
 *    exactement pour cela que l'appairage affiche une empreinte à comparer.
 *
 * Format transporté dans `messages.body` :
 *
 * ```
 * [[tomate:e2ee:v1]]<from>|<epk base64url>|<iv base64url>|<sig base64url>|<ct base64url>
 * ```
 *
 *  - `epk` : point P-256 non compressé (65 octets) de la clé éphémère de l'expéditeur ;
 *  - `iv` : 12 octets aléatoires ;
 *  - `sig` : ECDSA P-256/SHA-256 en `r || s` (64 octets), sur `aad|iv|ct` ;
 *  - `ct` : AES-256-GCM, tag de 16 octets inclus.
 */
object E2ee {

    const val PREFIX = "[[tomate:e2ee:v1]]"
    const val INFO = "tomate-e2ee-v1"
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val KEY_BYTES = 32

    /** Marge gardée sous la limite de 4000 caractères de `messages.body`. */
    const val MAX_PLAINTEXT_BYTES = 2600
    const val MAX_BODY_LENGTH = 4000

    @Volatile
    private var random: SecureRandom = SecureRandom()

    /** Injectable pour des tests déterministes ; jamais utilisé par l'app en production. */
    fun useRandomForTests(source: SecureRandom) {
        random = source
    }

    fun isEncrypted(body: String): Boolean = body.startsWith(PREFIX)

    fun encrypt(
        plaintext: String,
        conversationId: String,
        senderId: String,
        recipientAgreeKey: PublicKey,
        senderSignKey: PrivateKey,
    ): String {
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) throw E2eeException("Message vide.")
        if (bytes.size > MAX_PLAINTEXT_BYTES) {
            throw E2eeException("Message trop long pour être chiffré ($MAX_PLAINTEXT_BYTES octets maximum).")
        }
        require(conversationId.isNotBlank()) { "Conversation manquante." }
        require(senderId.isNotBlank()) { "Émetteur manquant." }

        val ephemeral: KeyPair = EcKeys.generate(random)
        val ephemeralPoint = EcKeys.encodePoint(ephemeral.public)
        val epk = b64(ephemeralPoint)
        val aad = associatedData(conversationId, senderId, epk)

        val key = messageKey(EcKeys.agree(ephemeral.private, recipientAgreeKey, random), ephemeralPoint)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val ciphertext = seal(key, iv, aad.toByteArray(Charsets.UTF_8), bytes)
        val ivText = b64(iv)
        val cipherText = b64(ciphertext)
        val signature = Ecdsa.signRaw(senderSignKey, signedBytes(aad, ivText, cipherText))

        val body = PREFIX + senderId + "|" + epk + "|" + ivText + "|" + b64(signature) + "|" + cipherText
        if (body.length > MAX_BODY_LENGTH) throw E2eeException("Message chiffré trop long pour le serveur.")
        return body
    }

    fun decrypt(
        message: E2eeMessage,
        expectedSenderId: String,
        recipientAgreeKey: PrivateKey,
        senderSignKey: PublicKey,
    ): String {
        if (message.fromId != expectedSenderId) {
            throw E2eeException("L'expéditeur du message chiffré ne correspond pas à celui de la conversation.")
        }
        val ephemeralPublic = EcKeys.decodePoint(fromB64(message.ephemeralKey))
        val signature = fromB64(message.signature)
        val aad = associatedData(message.conversationId, message.fromId, message.ephemeralKey)
        if (!Ecdsa.verifyRaw(senderSignKey, signedBytes(aad, message.iv, message.ciphertext), signature)) {
            throw E2eeException("Signature invalide : le message a été modifié ou n'a pas été écrit par cet expéditeur.")
        }
        val key = messageKey(EcKeys.agree(recipientAgreeKey, ephemeralPublic, random), EcKeys.encodePoint(ephemeralPublic))
        val plaintext = open(
            key,
            fromB64(message.iv),
            aad.toByteArray(Charsets.UTF_8),
            fromB64(message.ciphertext),
        )
        return plaintext.toString(Charsets.UTF_8)
    }

    /**
     * Numéro de sécurité affiché aux deux personnes : identique des deux côtés, calculé sur les
     * quatre clés publiques triées. Sert à détecter un serveur qui remplacerait une clé.
     */
    fun safetyNumber(
        firstId: String,
        firstAgreeKey: String,
        firstSignKey: String,
        secondId: String,
        secondAgreeKey: String,
        secondSignKey: String,
    ): String {
        val first = "$firstId:$firstAgreeKey:$firstSignKey"
        val second = "$secondId:$secondAgreeKey:$secondSignKey"
        val ordered = listOf(first, second).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(ordered.joinToString("|").toByteArray(Charsets.UTF_8))
        val digits = buildString {
            for (index in 0 until 8) {
                if (index > 0) append(' ')
                val value = ((digest[index * 2].toInt() and 0xFF) shl 8) or (digest[index * 2 + 1].toInt() and 0xFF)
                append(value.toString().padStart(5, '0'))
            }
        }
        return digits.trim()
    }

    private fun messageKey(shared: ByteArray, ephemeralPoint: ByteArray): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256").digest(ephemeralPoint)
        return Hkdf.sha256(shared, salt, INFO.toByteArray(Charsets.UTF_8), KEY_BYTES)
    }

    private fun associatedData(conversationId: String, senderId: String, epk: String): String =
        "$INFO|$conversationId|$senderId|$epk"

    private fun signedBytes(aad: String, iv: String, ciphertext: String): ByteArray =
        "$aad|$iv|$ciphertext".toByteArray(Charsets.UTF_8)

    private fun seal(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        if (iv.size != IV_BYTES) throw E2eeException("IV de taille invalide.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun open(key: ByteArray, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (iv.size != IV_BYTES) throw E2eeException("IV de taille invalide.")
        if (ciphertext.size < TAG_BITS / 8) throw E2eeException("Message chiffré tronqué.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return runCatching { cipher.doFinal(ciphertext) }
            .getOrElse { throw E2eeException("Déchiffrement impossible : la clé ne correspond pas ou le message est corrompu.") }
    }

    private fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun fromB64(text: String): ByteArray =
        runCatching { Base64.getUrlDecoder().decode(text) }
            .getOrElse { throw E2eeException("Encodage base64url invalide.") }
}

/** Message chiffré déjà décodé, avant vérification et déchiffrement. */
data class E2eeMessage(
    val fromId: String,
    val ephemeralKey: String,
    val iv: String,
    val signature: String,
    val ciphertext: String,
    val conversationId: String,
) {
    companion object {
        fun parse(body: String, conversationId: String): E2eeMessage? {
            if (!E2ee.isEncrypted(body)) return null
            val fields = body.removePrefix(E2ee.PREFIX).split('|')
            if (fields.size != 5) return null
            val from = fields[0]
            if (from.isBlank() || from.length > 64) return null
            if (fields.any { it.isBlank() }) return null
            return E2eeMessage(
                fromId = from,
                ephemeralKey = fields[1],
                iv = fields[2],
                signature = fields[3],
                ciphertext = fields[4],
                conversationId = conversationId,
            )
        }
    }
}

class E2eeException(message: String) : Exception(message)
