package tomate.net

import java.util.Base64
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tomate.core.crypto.EcKeys
import tomate.core.crypto.Ecdsa
import tomate.net.mesh.CourierStore
import tomate.net.mesh.MeshPacket
import tomate.net.mesh.MeshRouter
import tomate.net.mesh.MeshType

class MeshPacketTest {

    private fun packet(ttl: Int = 5, destination: String? = null) = MeshPacket(
        id = "p1",
        type = MeshType.MESSAGE,
        origin = "aaaaaaaaaaaaaaaa",
        destination = destination,
        ttl = ttl,
        createdAt = 1_000L,
        payload = "contenu opaque — 😀",
        signature = null,
    )

    @Test
    fun `un paquet fait l'aller-retour sans perdre le contenu`() {
        val original = packet(destination = "bbbb")
        val decoded = MeshPacket.decode(original.encode())
        assertNotNull(decoded)
        assertEquals(original, decoded)
        assertEquals("contenu opaque — 😀", decoded!!.payload)
    }

    @Test
    fun `un paquet malforme est refuse au lieu de lever une exception`() {
        assertNull(MeshPacket.decode(""))
        assertNull(MeshPacket.decode("T2|p1|M|origin||5|1000|-|"))
        assertNull(MeshPacket.decode("T1|p1|M|origin|-|5|1000|-|cGF5bG9hZA"))
        assertNull(MeshPacket.decode("T2|p1|Z|origin|-|5|1000|-|cGF5bG9hZA"))
        assertNull(MeshPacket.decode("T2|p1|M|origin|-|99|1000|-|cGF5bG9hZA"))
        assertNull(MeshPacket.decode("T2||M|origin|-|5|1000|-|cGF5bG9hZA"))
    }

    @Test
    fun `le tag de destinataire change chaque jour et reste stable dans la journee`() {
        val today = MeshPacket.recipientTag("user-1", 20_000L)
        assertEquals(today, MeshPacket.recipientTag("user-1", 20_000L))
        assertNotEquals(today, MeshPacket.recipientTag("user-1", 20_001L))
        assertNotEquals(today, MeshPacket.recipientTag("user-2", 20_000L))
    }
}

class MeshRouterTest {

    private var now = 10_000_000L
    private val router = MeshRouter(
        localTag = "moi",
        clock = { now },
        random = Random(7),
    )

    private fun packet(
        id: String = "p1",
        ttl: Int = 4,
        destination: String? = null,
        origin: String = "pair-a",
        createdAt: Long = now,
        signature: String? = null,
    ) = MeshPacket(
        id = id,
        type = MeshType.MESSAGE,
        origin = origin,
        destination = destination,
        ttl = ttl,
        createdAt = createdAt,
        payload = "chiffré",
        signature = signature,
    )

    @Test
    fun `un paquet qui me vise est livre`() {
        val decision = router.receive(packet(destination = "moi"))
        assertTrue(decision is MeshRouter.Decision.Deliver)
    }

    @Test
    fun `une diffusion est livree et non relayee`() {
        assertTrue(router.receive(packet(destination = null)) is MeshRouter.Decision.Deliver)
    }

    @Test
    fun `un paquet pour un autre est relaye avec un ttl decremente`() {
        val decision = router.receive(packet(destination = "autre", ttl = 4), incomingLink = "ble:pair-b")
        assertTrue(decision is MeshRouter.Decision.Forward)
        val forward = decision as MeshRouter.Decision.Forward
        assertEquals(3, forward.packet.ttl)
        assertEquals("ble:pair-b", forward.exceptLink)
        assertTrue("délai borné", forward.delayMs in 0..250)
    }

    @Test
    fun `un paquet deja vu est rejete`() {
        val first = router.receive(packet(id = "dup", destination = "autre"))
        assertTrue(first is MeshRouter.Decision.Forward)
        val second = router.receive(packet(id = "dup", destination = "autre"))
        assertEquals(MeshRouter.Decision.Drop(MeshRouter.Reason.Duplicate), second)
    }

    @Test
    fun `un ttl epuise est rejete`() {
        assertEquals(
            MeshRouter.Decision.Drop(MeshRouter.Reason.TtlExhausted),
            router.receive(packet(ttl = 0, destination = "autre")),
        )
    }

    @Test
    fun `un paquet trop vieux est rejete`() {
        assertEquals(
            MeshRouter.Decision.Drop(MeshRouter.Reason.Expired),
            router.receive(packet(createdAt = now - MeshPacket.MAX_AGE_MS - 1, destination = "moi")),
        )
    }

    @Test
    fun `le relais peut etre desactive par l'utilisateur`() {
        val disabled = MeshRouter(localTag = "moi", clock = { now }, forwardingEnabled = { false })
        assertEquals(
            MeshRouter.Decision.Drop(MeshRouter.Reason.ForwardingDisabled),
            disabled.receive(packet(destination = "autre")),
        )
        // Même désactivé, un message qui me vise est livré.
        assertTrue(disabled.receive(packet(id = "encore", destination = "moi")) is MeshRouter.Decision.Deliver)
    }

    @Test
    fun `une signature invalide interdit le relais`() {
        val originKeys = EcKeys.generate()
        val signedRouter = MeshRouter(
            localTag = "moi",
            clock = { now },
            verifier = MeshRouter.verifierFor { if (it == "pair-a") originKeys.public else null },
        )
        val signed = packet(id = "s1", destination = "autre").copy(
            signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Ecdsa.signRaw(originKeys.private, packet(id = "s1", destination = "autre").signedBytes())),
        )
        assertTrue(signedRouter.receive(signed) is MeshRouter.Decision.Forward)

        val tampered = signed.copy(payload = "autre contenu")
        assertEquals(
            MeshRouter.Decision.Drop(MeshRouter.Reason.InvalidSignature),
            signedRouter.receive(tampered),
        )

        val unknownOrigin = signed.copy(id = "s3", origin = "inconnu")
        assertEquals(
            MeshRouter.Decision.Drop(MeshRouter.Reason.InvalidSignature),
            signedRouter.receive(unknownOrigin),
        )
    }

    @Test
    fun `la memoire de deduplication reste bornee`() {
        repeat(MeshRouter.MAX_SEEN + 50) { index ->
            router.receive(packet(id = "boucle-$index", destination = "autre"))
        }
        assertTrue("taille=${router.seenCount()}", router.seenCount() <= MeshRouter.MAX_SEEN)
    }
}

class CourierStoreTest {

    private var now = 5_000_000L
    private val store = CourierStore(clock = { now }, capacity = 3)

    private fun courier(id: String, destination: String? = "tag-b", createdAt: Long = now) = MeshPacket(
        id = id,
        type = MeshType.COURIER,
        origin = "pair-a",
        destination = destination,
        ttl = 4,
        createdAt = createdAt,
        payload = "chiffré",
    )

    @Test
    fun `un paquet sans destinataire n'est pas accepte`() {
        assertFalse(store.deposit(courier("x", destination = null)))
    }

    @Test
    fun `la capacite est bornee et le plus ancien part le premier`() {
        assertTrue(store.deposit(courier("a")))
        assertTrue(store.deposit(courier("b")))
        assertTrue(store.deposit(courier("c")))
        assertTrue(store.deposit(courier("d")))
        assertEquals(3, store.size())
        val ids = store.due().map { it.id }
        assertFalse("le plus ancien doit être parti", ids.contains("a"))
        assertTrue(ids.containsAll(listOf("b", "c", "d")))
    }

    @Test
    fun `un paquet perime est purgé`() {
        store.deposit(courier("vieux"))
        now += MeshPacket.MAX_AGE_MS + 1
        assertEquals(0, store.size())
        assertTrue(store.due().isEmpty())
    }

    @Test
    fun `le courrier se retrouve par tag de destinataire et s'efface apres remise`() {
        store.deposit(courier("a", destination = "tag-b"))
        store.deposit(courier("b", destination = "tag-c"))
        assertEquals(listOf("a"), store.forTag("tag-b").map { it.id })
        store.acknowledge("a")
        assertTrue(store.forTag("tag-b").isEmpty())
        assertEquals(1, store.size())
    }
}
