package tomate.data

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tomate.core.crypto.E2ee
import tomate.data.e2ee.E2eeDirectory
import tomate.data.e2ee.E2eeEngine
import tomate.data.e2ee.E2eeService
import tomate.data.e2ee.E2eeStatus
import tomate.data.e2ee.E2eeStore
import tomate.data.e2ee.FileE2eeStore
import tomate.data.e2ee.PeerKeySource
import tomate.data.e2ee.PeerPublicKeys
import tomate.data.security.InMemoryCryptoBox

/** Annuaire simulé : sert à vérifier qu'une clé épinglée ne peut pas être remplacée. */
private class FakeDirectory : E2eeDirectory {
    val keys = mutableMapOf<String, PeerPublicKeys>()
    var published: Pair<String, String>? = null
    var failPublication = false

    override suspend fun fetch(userId: String): PeerPublicKeys? = keys[userId]

    override suspend fun publish(identity: tomate.data.e2ee.E2eeIdentity): Boolean {
        published = identity.agreePublic to identity.signPublic
        return !failPublication
    }
}

class E2eeServiceTest {

    private val aliceId = "11111111-1111-4111-8111-111111111111"
    private val bobId = "22222222-2222-4222-8222-222222222222"
    private val conversation = "conv-1"

    private fun service(store: E2eeStore, directory: E2eeDirectory = FakeDirectory()) =
        E2eeService(store, directory)

    @Test
    fun `l'identite est creee une seule fois et persiste sur disque`() {
        val dir = File("build/tmp/e2ee-store-${System.nanoTime()}").apply { mkdirs() }
        val file = File(dir, "identity.json")
        val box = InMemoryCryptoBox()

        val first = service(FileE2eeStore(file, box))
        assertEquals(E2eeStatus.NotConfigured, first.status)
        val identity = first.ensureIdentity(aliceId)
        assertEquals(E2eeStatus.Operational, first.status)

        // Nouvelle instance : la même identité doit être relue depuis le fichier.
        val second = service(FileE2eeStore(file, box))
        assertEquals(identity.agreePublic, second.identity()!!.agreePublic)
        assertEquals(identity.signPublic, second.identity()!!.signPublic)
        assertEquals(conversation, conversation)
    }

    @Test
    fun `les cles privees ne sont pas ecrites en clair`() {
        val dir = File("build/tmp/e2ee-store-${System.nanoTime()}").apply { mkdirs() }
        val file = File(dir, "identity.json")
        val service = service(FileE2eeStore(file, InMemoryCryptoBox()))
        val identity = service.ensureIdentity(aliceId)
        val content = file.readText()
        assertFalse("La clé privée d'accord ne doit pas apparaître en clair", content.contains(identity.agreePrivate))
        assertFalse("La clé privée de signature ne doit pas apparaître en clair", content.contains(identity.signPrivate))
        // En revanche les clés publiques sont bien lisibles (elles sont publiques).
        assertTrue(content.contains(identity.agreePublic))
    }

    @Test
    fun `deux appareils se parlent chiffre`() = runTest {
        val directory = FakeDirectory()
        val alice = service(FileE2eeStore(File("build/tmp/alice-${System.nanoTime()}.json"), InMemoryCryptoBox()), directory)
        val bob = service(FileE2eeStore(File("build/tmp/bob-${System.nanoTime()}.json"), InMemoryCryptoBox()), directory)

        val aliceIdentity = alice.ensureIdentity(aliceId)
        val bobIdentity = bob.ensureIdentity(bobId)
        directory.keys[bobId] = PeerPublicKeys(bobId, "bob", bobIdentity.agreePublic, bobIdentity.signKey(), PeerKeySource.Directory)
        directory.keys[aliceId] = PeerPublicKeys(aliceId, "alice", aliceIdentity.agreePublic, aliceIdentity.signPublic, PeerKeySource.Directory)

        val outgoing = alice.prepareOutgoing(conversation, aliceId, bobId, "On se voit demain ?")
        assertTrue(outgoing is E2eeEngine.Cipher.Ok)
        val sealed = outgoing as E2eeEngine.Cipher.Ok
        assertTrue("le message doit être chiffré", sealed.encrypted)
        assertTrue(E2ee.isEncrypted(sealed.text))
        assertFalse(sealed.text.contains("demain"))

        val opened = bob.openIncoming(conversation, aliceId, sealed.text)
        assertEquals(E2eeEngine.Cipher.Ok("On se voit demain ?", encrypted = true), opened)

        // Réponse chiffrée dans l'autre sens.
        val reply = (bob.prepareOutgoing(conversation, bobId, aliceId, "Oui, 18 h.") as E2eeEngine.Cipher.Ok)
        assertEquals(E2eeEngine.Cipher.Ok("Oui, 18 h.", encrypted = true), alice.openIncoming(conversation, bobId, reply.text))
    }

    @Test
    fun `un groupe n'est jamais annonce comme chiffre`() = runTest {
        val service = service(FileE2eeStore(File("build/tmp/group-${System.nanoTime()}.json"), InMemoryCryptoBox()))
        service.ensureIdentity(aliceId)
        val cipher = service.prepareOutgoing(conversation, aliceId, peerId = null, plaintext = "coucou le groupe")
        assertEquals(E2eeEngine.Cipher.Ok("coucou le groupe", encrypted = false), cipher)
    }

    @Test
    fun `sans cle du contact le message part en clair et l'interface est avertie`() = runTest {
        val service = service(FileE2eeStore(File("build/tmp/nokeys-${System.nanoTime()}.json"), InMemoryCryptoBox()))
        service.ensureIdentity(aliceId)
        val cipher = service.prepareOutgoing(conversation, aliceId, bobId, "texte")
        assertEquals(E2eeEngine.Cipher.Ok("texte", encrypted = false), cipher)
    }

    @Test
    fun `une cle epinglee par appairage ne peut pas etre remplacee par l'annuaire`() = runTest {
        val directory = FakeDirectory()
        val aliceStore = FileE2eeStore(File("build/tmp/pin-${System.nanoTime()}.json"), InMemoryCryptoBox())
        val alice = service(aliceStore, directory)
        val bob = service(FileE2eeStore(File("build/tmp/pinbob-${System.nanoTime()}.json"), InMemoryCryptoBox()), directory)
        val aliceIdentity = alice.ensureIdentity(aliceId)
        val bobIdentity = bob.ensureIdentity(bobId)
        // Bob obtient les clés d'Alice par l'annuaire : sans la clé de l'expéditeur, un message
        // chiffré ne peut pas être vérifié (c'est voulu : on ne devine jamais une identité).
        directory.keys[aliceId] = PeerPublicKeys(aliceId, "alice", aliceIdentity.agreePublic, aliceIdentity.signPublic, PeerKeySource.Directory)

        // Appairage hors bande : les vraies clés de Bob.
        val link = tomate.core.crypto.PairingLink.encode(
            tomate.core.crypto.PairingPayload(bobId, "bob", bobIdentity.agreePublic, bobIdentity.signPublic),
        )
        alice.registerPeer(alice.parsePairingLink(link)!!)

        // L'annuaire (ou un serveur malveillant) propose une autre clé : elle doit être ignorée.
        val impostor = bob.serviceIdentityForTest()
        directory.keys[bobId] = PeerPublicKeys(bobId, "bob", impostor.agreePublic, impostor.signPublic, PeerKeySource.Directory)

        val cipher = alice.prepareOutgoing(conversation, aliceId, bobId, "message sûr") as E2eeEngine.Cipher.Ok
        assertTrue(cipher.encrypted)
        // Bob déchiffre avec SES clés : preuve que la clé épinglée a bien été utilisée.
        assertEquals(E2eeEngine.Cipher.Ok("message sûr", encrypted = true), bob.openIncoming(conversation, aliceId, cipher.text))
        assertEquals(PeerKeySource.Pairing, alice.peerSource(bobId))
    }

    @Test
    fun `un message chiffre illisible est signale sans planter`() = runTest {
        val service = service(FileE2eeStore(File("build/tmp/broken-${System.nanoTime()}.json"), InMemoryCryptoBox()))
        service.ensureIdentity(aliceId)
        val result = service.openIncoming(conversation, bobId, "[[tomate:e2ee:v1]]pas|un|vrai|message|chiffre")
        assertTrue(result is E2eeEngine.Cipher.Failed)
        assertNotNull((result as E2eeEngine.Cipher.Failed).reason)
    }

    @Test
    fun `le numero de securite est disponible des que les deux cotes ont les cles`() = runTest {
        val directory = FakeDirectory()
        val alice = service(FileE2eeStore(File("build/tmp/sn-a-${System.nanoTime()}.json"), InMemoryCryptoBox()), directory)
        val bob = service(FileE2eeStore(File("build/tmp/sn-b-${System.nanoTime()}.json"), InMemoryCryptoBox()), directory)
        val aliceIdentity = alice.ensureIdentity(aliceId)
        val bobIdentity = bob.ensureIdentity(bobId)
        directory.keys[bobId] = PeerPublicKeys(bobId, "bob", bobIdentity.agreePublic, bobIdentity.signPublic, PeerKeySource.Directory)
        directory.keys[aliceId] = PeerPublicKeys(aliceId, "alice", aliceIdentity.agreePublic, aliceIdentity.signPublic, PeerKeySource.Directory)

        val fromAlice = alice.safetyNumber(bobId)
        val fromBob = bob.safetyNumber(aliceId)
        assertNotNull(fromAlice)
        assertEquals(fromAlice, fromBob)
    }

    @Test
    fun `la publication echoue proprement hors ligne`() = runTest {
        val directory = FakeDirectory().apply { failPublication = true }
        val service = service(FileE2eeStore(File("build/tmp/pub-${System.nanoTime()}.json"), InMemoryCryptoBox()), directory)
        service.ensureIdentity(aliceId)
        val result = service.publishIdentityKeys()
        assertTrue(result is E2eeEngine.E2eeResult.Failed)
    }

    @Test
    fun `publier n'envoie que les cles publiques`() = runTest {
        val directory = FakeDirectory()
        val service = service(FileE2eeStore(File("build/tmp/pub2-${System.nanoTime()}.json"), InMemoryCryptoBox()), directory)
        val identity = service.ensureIdentity(aliceId)
        assertTrue(service.publishIdentityKeys() is E2eeEngine.E2eeResult.Ok)
        assertEquals(identity.agreePublic to identity.signPublic, directory.published)
        assertNull(directory.published!!.first.takeIf { it == identity.agreePrivate })
    }
}

/** Petite aide : renvoie l'identité d'un service (les clés publiques sont publiques). */
private fun E2eeService.serviceIdentityForTest(): tomate.data.e2ee.E2eeIdentity = identity()!!

private fun tomate.data.e2ee.E2eeIdentity.signKey(): String = signPublic
