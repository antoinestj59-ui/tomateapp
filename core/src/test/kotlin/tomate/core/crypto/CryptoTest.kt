package tomate.core.crypto

import java.security.KeyPair
import java.util.HexFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HkdfTest {

    /** Vecteur de test n°1 du RFC 5869, octet pour octet. */
    @Test
    fun `hkdf respecte le vecteur du RFC 5869`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())
        val okm = Hkdf.sha256(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            HexFormat.of().formatHex(okm),
        )
    }

    @Test
    fun `hkdf est deterministe et sensible au sel`() {
        val ikm = ByteArray(32) { 1 }
        val first = Hkdf.sha256(ikm, ByteArray(8) { 2 }, byteArrayOf(9), 32)
        val second = Hkdf.sha256(ikm, ByteArray(8) { 2 }, byteArrayOf(9), 32)
        val other = Hkdf.sha256(ikm, ByteArray(8) { 3 }, byteArrayOf(9), 32)
        assertTrue(first.contentEquals(second))
        assertFalse(first.contentEquals(other))
    }
}

class EcdsaTest {

    @Test
    fun `la conversion DER vers raw et retour preserve la signature`() {
        val keys = EcKeys.generate()
        val data = "tomate".toByteArray()
        val der = Ecdsa.signDer(keys.private, data)
        val raw = Ecdsa.derToRaw(der)
        assertEquals(Ecdsa.RAW_SIGNATURE_BYTES, raw.size)
        assertTrue(Ecdsa.verifyDer(keys.public, data, Ecdsa.rawToDer(raw)))
        assertTrue(Ecdsa.verifyRaw(keys.public, data, raw))
    }

    @Test
    fun `une signature alteree est refusee`() {
        val keys = EcKeys.generate()
        val data = "tomate".toByteArray()
        val raw = Ecdsa.signRaw(keys.private, data)
        raw[10] = (raw[10] + 1).toByte()
        assertFalse(Ecdsa.verifyRaw(keys.public, data, raw))
    }
}

class E2eeTest {

    private val alice: KeyPair = EcKeys.generate()
    private val bob: KeyPair = EcKeys.generate()
    private val bobSigning: KeyPair = EcKeys.generate()
    private val aliceSigning: KeyPair = EcKeys.generate()
    private val conversation = "3f0e7d1a-1111-4a2b-9c3d-000000000001"
    private val aliceId = "11111111-1111-4111-8111-111111111111"
    private val bobId = "22222222-2222-4222-8222-222222222222"

    private fun encrypted(text: String = "Salut Bob, on se voit demain ?"): String =
        E2ee.encrypt(text, conversation, aliceId, bob.public, aliceSigning.private)

    @Test
    fun `un message chiffre se dechiffre avec la cle du destinataire`() {
        val body = encrypted()
        assertTrue(E2ee.isEncrypted(body))
        val message = E2eeMessage.parse(body, conversation)
        assertNotNull(message)
        val plaintext = E2ee.decrypt(message!!, aliceId, bob.private, aliceSigning.public)
        assertEquals("Salut Bob, on se voit demain ?", plaintext)
    }

    @Test
    fun `le contenu chiffre ne laisse pas le texte en clair`() {
        val body = encrypted("mot-de-passe-secret")
        assertFalse(body.contains("mot-de-passe-secret"))
        assertFalse(body.contains("Salut"))
    }

    @Test
    fun `un ciphertext modifie est rejete`() {
        val body = encrypted()
        val message = E2eeMessage.parse(body, conversation)!!
        val tampered = message.copy(ciphertext = message.ciphertext.dropLast(4) + "AAAA")
        try {
            E2ee.decrypt(tampered, aliceId, bob.private, aliceSigning.public)
            fail("Un message modifié ne doit pas être accepté.")
        } catch (expected: E2eeException) {
            assertTrue(expected.message!!.contains("Signature") || expected.message!!.contains("Déchiffrement"))
        }
    }

    @Test
    fun `une signature d'un autre emetteur est rejetee`() {
        val body = E2ee.encrypt("coucou", conversation, aliceId, bob.public, aliceSigning.private)
        val message = E2eeMessage.parse(body, conversation)!!
        try {
            E2ee.decrypt(message, aliceId, bob.private, bobSigning.public)
            fail("La signature doit être vérifiée avec la clé de l'expéditeur annoncé.")
        } catch (expected: E2eeException) {
            assertTrue(expected.message!!.contains("Signature"))
        }
    }

    @Test
    fun `usurper l'identite de l'emetteur est rejete`() {
        val body = E2ee.encrypt("coucou", conversation, bobId, bob.public, aliceSigning.private)
        val message = E2eeMessage.parse(body, conversation)!!
        try {
            E2ee.decrypt(message, aliceId, bob.private, aliceSigning.public)
            fail("L'identifiant d'expéditeur doit correspondre.")
        } catch (expected: E2eeException) {
            assertTrue(expected.message!!.contains("expéditeur"))
        }
    }

    @Test
    fun `la taille du corps reste sous la limite serveur`() {
        val body = encrypted("é".repeat(E2ee.MAX_PLAINTEXT_BYTES / 2))
        assertTrue("body=${body.length}", body.length <= E2ee.MAX_BODY_LENGTH)
    }

    @Test
    fun `un message trop long est refuse au lieu d'etre tronque`() {
        try {
            encrypted("x".repeat(E2ee.MAX_PLAINTEXT_BYTES + 1))
            fail("Un message trop long doit lever une erreur explicite.")
        } catch (expected: E2eeException) {
            assertTrue(expected.message!!.contains("trop long"))
        }
    }

    @Test
    fun `le numero de securite est identique des deux cotes`() {
        val fromAlice = E2ee.safetyNumber(
            aliceId, EcKeys.encodePublic(alice.public), EcKeys.encodePublic(aliceSigning.public),
            bobId, EcKeys.encodePublic(bob.public), EcKeys.encodePublic(bobSigning.public),
        )
        val fromBob = E2ee.safetyNumber(
            bobId, EcKeys.encodePublic(bob.public), EcKeys.encodePublic(bobSigning.public),
            aliceId, EcKeys.encodePublic(alice.public), EcKeys.encodePublic(aliceSigning.public),
        )
        assertEquals(fromAlice, fromBob)
        assertTrue(fromAlice.matches(Regex("^\\d{5} \\d{5} \\d{5} \\d{5} \\d{5} \\d{5} \\d{5} \\d{5}$")))
    }

    @Test
    fun `deux chiffrements du meme texte donnent des corps differents`() {
        val first = encrypted("même texte")
        val second = encrypted("même texte")
        assertFalse(first == second)
    }

    @Test
    fun `une cle de dechiffrement etrangere echoue proprement`() {
        val body = encrypted()
        val message = E2eeMessage.parse(body, conversation)!!
        try {
            E2ee.decrypt(message, aliceId, EcKeys.generate().private, aliceSigning.public)
            fail("Une autre clé privée ne doit pas déchiffrer le message.")
        } catch (expected: E2eeException) {
            assertTrue(expected is E2eeException)
        }
    }
}

class PairingLinkTest {

    private val keys = EcKeys.generate()
    private val signing = EcKeys.generate()
    private val payload = PairingPayload(
        userId = "11111111-1111-4111-8111-111111111111",
        handle = "alice",
        agreeKey = EcKeys.encodePublic(keys.public),
        signKey = EcKeys.encodePublic(signing.public),
    )

    @Test
    fun `un lien d'appairage fait l'aller-retour`() {
        val link = payload.toLink()
        assertTrue(link.startsWith("tomate://pair?"))
        val parsed = PairingLink.parse(link)
        assertEquals(payload, parsed)
    }

    @Test
    fun `les variantes collees a la main sont acceptees`() {
        val link = payload.toLink()
        assertNotNull(PairingLink.parse(link))
        assertNotNull(PairingLink.parse(link.removePrefix("tomate://")))
        assertNotNull(PairingLink.parse(link.substringAfter('?')))
    }

    @Test
    fun `un lien incomplet ou falsifie est refuse`() {
        assertNull(PairingLink.parse(""))
        assertNull(PairingLink.parse("https://example.com/pair?u=1"))
        assertNull(PairingLink.parse(payload.toLink().replace(payload.userId, "pas-un-uuid")))
        assertNull(PairingLink.parse(payload.toLink().replace(payload.signKey, "AAAA")))
        assertNull(PairingLink.parse(payload.toLink().replace("n=alice", "n=ALICE")))
        assertNull(PairingLink.parse(payload.toLink().replace("v=1", "v=2")))
    }

    @Test
    fun `l'empreinte affichee derive de la cle de signature`() {
        assertEquals(EcKeys.fingerprint(signing.public), payload.fingerprint)
    }
}
