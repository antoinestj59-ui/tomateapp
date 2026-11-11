package tomate.data.e2ee

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tomate.core.crypto.EcKeys
import tomate.data.security.CryptoBox

/**
 * Identité de chiffrement d'un appareil.
 *
 * Les clés **privées** sont stockées enveloppées par [CryptoBox] (Keystore Android en production)
 * et ne sont jamais envoyées au serveur ; seules les clés publiques sortent de l'appareil.
 */
data class E2eeIdentity(
    val userId: String,
    val agreePrivate: String,
    val agreePublic: String,
    val signPrivate: String,
    val signPublic: String,
) {
    val fingerprint: String? get() = EcKeys.fingerprint(signPublic)
}

enum class PeerKeySource { Pairing, Directory }

/** Clés publiques d'un contact, reçues par appairage (QR/lien/NFC) ou lues dans l'annuaire. */
data class PeerPublicKeys(
    val userId: String,
    val handle: String,
    val agreeKey: String,
    val signKey: String,
    val source: PeerKeySource,
) {
    val fingerprint: String? get() = EcKeys.fingerprint(signKey)
}

interface E2eeStore {
    fun identity(): E2eeIdentity?
    fun saveIdentity(identity: E2eeIdentity)
    fun peer(userId: String): PeerPublicKeys?
    fun savePeer(peer: PeerPublicKeys)
    fun peers(): List<PeerPublicKeys>
    fun clear()
}

/**
 * Stockage fichier, dans le répertoire privé de l'application.
 *
 * Une base Room serait plus lourde pour six champs : le fichier est petit, atomique (écriture
 * puis renommage), et les clés privées y sont chiffrées par le Keystore. Un fichier corrompu est
 * ignoré (l'identité sera régénérée) au lieu de faire planter l'app.
 */
class FileE2eeStore(
    private val file: File,
    private val box: CryptoBox,
) : E2eeStore {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StoredIdentity(
        val userId: String,
        val agreePrivate: String,
        val agreePublic: String,
        val signPrivate: String,
        val signPublic: String,
    )

    @Serializable
    private data class StoredPeer(
        val userId: String,
        val handle: String,
        val agreeKey: String,
        val signKey: String,
        val source: String,
    )

    @Serializable
    private data class Document(
        val version: Int = 1,
        val identity: StoredIdentity? = null,
        val peers: List<StoredPeer> = emptyList(),
    )

    override fun identity(): E2eeIdentity? {
        val stored = read().identity ?: return null
        val agreePrivate = box.decryptOrNull(stored.agreePrivate) ?: return null
        val signPrivate = box.decryptOrNull(stored.signPrivate) ?: return null
        return E2eeIdentity(
            userId = stored.userId,
            agreePrivate = agreePrivate,
            agreePublic = stored.agreePublic,
            signPrivate = signPrivate,
            signPublic = stored.signPublic,
        )
    }

    override fun saveIdentity(identity: E2eeIdentity) {
        write(
            read().copy(
                identity = StoredIdentity(
                    userId = identity.userId,
                    agreePrivate = box.encrypt(identity.agreePrivate),
                    agreePublic = identity.agreePublic,
                    signPrivate = box.encrypt(identity.signPrivate),
                    signPublic = identity.signPublic,
                ),
            ),
        )
    }

    override fun peer(userId: String): PeerPublicKeys? =
        read().peers.firstOrNull { it.userId == userId }?.let { stored ->
            PeerPublicKeys(
                userId = stored.userId,
                handle = stored.handle,
                agreeKey = stored.agreeKey,
                signKey = stored.signKey,
                source = runCatching { PeerKeySource.valueOf(stored.source) }.getOrDefault(PeerKeySource.Directory),
            )
        }

    override fun savePeer(peer: PeerPublicKeys) {
        val document = read()
        val peers = document.peers.filterNot { it.userId == peer.userId } + StoredPeer(
            userId = peer.userId,
            handle = peer.handle,
            agreeKey = peer.agreeKey,
            signKey = peer.signKey,
            source = peer.source.name,
        )
        write(document.copy(peers = peers))
    }

    override fun peers(): List<PeerPublicKeys> = read().peers.mapNotNull { stored ->
        val source = runCatching { PeerKeySource.valueOf(stored.source) }.getOrDefault(PeerKeySource.Directory)
        if (EcKeys.fingerprint(stored.agreeKey) == null || EcKeys.fingerprint(stored.signKey) == null) {
            null
        } else {
            PeerPublicKeys(stored.userId, stored.handle, stored.agreeKey, stored.signKey, source)
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }

    private fun read(): Document {
        if (!file.exists()) return Document()
        val text = runCatching { file.readText() }.getOrNull() ?: return Document()
        return runCatching { json.decodeFromString(Document.serializer(), text) }.getOrDefault(Document())
    }

    private fun write(document: Document) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json.encodeToString(Document.serializer(), document))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }
}

/** Store vide : utilisé quand aucune identité n'a encore été créée (tests, premier lancement). */
class EmptyE2eeStore : E2eeStore {
    private var identity: E2eeIdentity? = null
    private val peers = mutableMapOf<String, PeerPublicKeys>()

    override fun identity(): E2eeIdentity? = identity
    override fun saveIdentity(identity: E2eeIdentity) { this.identity = identity }
    override fun peer(userId: String): PeerPublicKeys? = peers[userId]
    override fun savePeer(peer: PeerPublicKeys) { peers[peer.userId] = peer }
    override fun peers(): List<PeerPublicKeys> = peers.values.toList()
    override fun clear() {
        identity = null
        peers.clear()
    }
}
