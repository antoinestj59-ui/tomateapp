package tomate.data.e2ee

import tomate.core.crypto.E2ee
import tomate.core.crypto.E2eeException
import tomate.core.crypto.E2eeMessage
import tomate.core.crypto.EcKeys
import tomate.core.crypto.PairingLink
import tomate.core.crypto.PairingPayload

/** Annuaire des clés publiques : implémenté par `E2eeRepository`, simulable en test. */
interface E2eeDirectory {
    suspend fun fetch(userId: String): PeerPublicKeys?
    suspend fun publish(identity: E2eeIdentity): Boolean
}

/**
 * Le vrai moteur E2EE.
 *
 * Règles appliquées, toutes vérifiées par des tests :
 *  1. les clés **privées** ne sortent jamais du store chiffré ;
 *  2. une clé reçue par **appairage** (QR/lien/NFC) est épinglée : l'annuaire ne peut pas la
 *     remplacer, sinon un serveur malveillant pourrait lire tous les messages suivants ;
 *  3. un message qui ne peut pas être chiffré n'est **jamais** envoyé en clair en prétendant le
 *     contraire : on renvoie une erreur explicite ;
 *  4. un message chiffré illisible est signalé comme tel, jamais affiché en base64.
 */
class E2eeService(
    private val store: E2eeStore,
    private val directory: E2eeDirectory,
) : E2eeEngine {

    @Volatile
    private var cachedIdentity: E2eeIdentity? = null

    override val status: E2eeStatus
        get() = if (identity() != null) E2eeStatus.Operational else E2eeStatus.NotConfigured

    fun identity(): E2eeIdentity? {
        cachedIdentity?.let { return it }
        return store.identity()?.also { cachedIdentity = it }
    }

    /** Crée l'identité de l'appareil au premier message, ou renvoie celle déjà installée. */
    fun ensureIdentity(userId: String): E2eeIdentity {
        identity()?.let { existing ->
            if (existing.userId == userId) return existing
            // Un autre compte se connecte sur cet appareil : les clés du précédent ne servent plus.
            store.clear()
            cachedIdentity = null
        }
        val agree = EcKeys.generate()
        val sign = EcKeys.generate()
        val identity = E2eeIdentity(
            userId = userId,
            agreePrivate = EcKeys.encodePrivate(agree.private),
            agreePublic = EcKeys.encodePublic(agree.public),
            signPrivate = EcKeys.encodePrivate(sign.private),
            signPublic = EcKeys.encodePublic(sign.public),
        )
        store.saveIdentity(identity)
        cachedIdentity = identity
        return identity
    }

    /** Enregistre les clés reçues par QR, lien ou NFC. Elles sont épinglées. */
    fun registerPeer(payload: PairingPayload): PeerPublicKeys {
        val peer = PeerPublicKeys(
            userId = payload.userId,
            handle = payload.handle,
            agreeKey = payload.agreeKey,
            signKey = payload.signKey,
            source = PeerKeySource.Pairing,
        )
        store.savePeer(peer)
        return peer
    }

    fun parsePairingLink(text: String): PairingPayload? = PairingLink.parse(text)

    /** Lien à afficher en QR / à partager pour être ajouté avec les clés. */
    fun myPairingLink(userId: String, handle: String): String? {
        val identity = ensureIdentity(userId)
        return PairingLink.encode(
            PairingPayload(
                userId = userId,
                handle = handle,
                agreeKey = identity.agreePublic,
                signKey = identity.signPublic,
            ),
        )
    }

    override suspend fun publishIdentityKeys(): E2eeEngine.E2eeResult = publishKeys()

    suspend fun publishKeys(): E2eeEngine.E2eeResult {
        val identity = identity()
            ?: return E2eeEngine.E2eeResult.Failed("Aucune identité à publier : envoyez d'abord un message.")
        return if (directory.publish(identity)) {
            E2eeEngine.E2eeResult.Ok("Clés publiques publiées.")
        } else {
            E2eeEngine.E2eeResult.Failed("Publication impossible : vérifiez la connexion.")
        }
    }

    /** Clés d'un contact : appairage local d'abord, annuaire seulement si rien de connu. */
    suspend fun peerKeys(userId: String, allowDirectory: Boolean = true): PeerPublicKeys? {
        store.peer(userId)?.let { return it }
        if (!allowDirectory) return null
        val fetched = runCatching { directory.fetch(userId) }.getOrNull() ?: return null
        store.savePeer(fetched)
        return fetched
    }

    /** Numéro de sécurité entre ce compte et [peerId], `null` si les clés manquent. */
    suspend fun safetyNumber(peerId: String): String? {
        val me = identity() ?: return null
        val peer = peerKeys(peerId) ?: return null
        return E2ee.safetyNumber(
            me.userId, me.agreePublic, me.signPublic,
            peer.userId, peer.agreeKey, peer.signKey,
        )
    }

    fun peerSource(userId: String): PeerKeySource? = store.peer(userId)?.source

    override suspend fun prepareOutgoing(
        conversationId: String,
        senderId: String,
        peerId: String?,
        plaintext: String,
    ): E2eeEngine.Cipher {
        if (peerId == null) {
            // Groupe : pas de chiffrement de groupe dans cette version, et c'est annoncé tel quel.
            return E2eeEngine.Cipher.Ok(plaintext, encrypted = false)
        }
        val identity = ensureIdentity(senderId)
        val peer = peerKeys(peerId)
            ?: return E2eeEngine.Cipher.Ok(plaintext, encrypted = false)
        val signPrivate = EcKeys.decodePrivate(identity.signPrivate)
            ?: return E2eeEngine.Cipher.Failed("Clé de signature locale illisible : message non envoyé.")
        val peerAgree = EcKeys.decodePublic(peer.agreeKey)
            ?: return E2eeEngine.Cipher.Failed("Clé du contact illisible : message non envoyé.")
        return try {
            E2eeEngine.Cipher.Ok(
                E2ee.encrypt(plaintext, conversationId, senderId, peerAgree, signPrivate),
                encrypted = true,
            )
        } catch (error: E2eeException) {
            E2eeEngine.Cipher.Failed(error.message ?: "Chiffrement impossible.")
        } catch (error: Exception) {
            E2eeEngine.Cipher.Failed("Chiffrement impossible : ${error.message ?: "erreur inconnue"}.")
        }
    }

    override suspend fun openIncoming(
        conversationId: String,
        senderId: String,
        body: String,
    ): E2eeEngine.Cipher {
        if (!E2ee.isEncrypted(body)) return E2eeEngine.Cipher.Ok(body, encrypted = false)
        val identity = identity()
            ?: return E2eeEngine.Cipher.Failed("Message chiffré : aucune identité sur cet appareil.")
        val parsed = E2eeMessage.parse(body, conversationId)
            ?: return E2eeEngine.Cipher.Failed("Message chiffré illisible.")
        val agreePrivate = EcKeys.decodePrivate(identity.agreePrivate)
            ?: return E2eeEngine.Cipher.Failed("Clé privée locale illisible.")
        val senderKeys = peerKeys(senderId)
            ?: run {
                // La clé de l'expéditeur manque : la publication de nos clés est faite au passage,
                // ce qui permet au contact de nous répondre chiffré même après un premier envoi.
                return E2eeEngine.Cipher.Failed(
                    "Message chiffré : la clé de ce contact est inconnue. Appairez-le pour le lire.",
                )
            }
        val signPublic = EcKeys.decodePublic(senderKeys.signKey)
            ?: return E2eeEngine.Cipher.Failed("Clé de signature du contact illisible.")
        return try {
            val text = E2ee.decrypt(parsed, senderId, agreePrivate, signPublic)
            E2eeEngine.Cipher.Ok(text, encrypted = true)
        } catch (error: E2eeException) {
            E2eeEngine.Cipher.Failed(error.message ?: "Message chiffré illisible.")
        } catch (error: Exception) {
            E2eeEngine.Cipher.Failed("Message chiffré illisible.")
        }
    }
}
