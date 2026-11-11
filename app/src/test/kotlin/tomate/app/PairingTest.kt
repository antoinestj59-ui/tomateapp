package tomate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tomate.app.pairing.PairingNfc
import tomate.app.pairing.PairingQr
import tomate.core.crypto.EcKeys
import tomate.core.crypto.PairingLink
import tomate.core.crypto.PairingPayload

/**
 * Appairage : QR et NFC transportent le même lien, avec les clés publiques.
 *
 * Ce qui est vérifié ici : le QR se génère réellement en pixels (taille, contraste), l'enregistrement
 * NDEF fait l'aller-retour sans perdre une clé. Ce qui n'est pas vérifié : scanner avec une caméra
 * ou approcher une étiquette d'un vrai téléphone (aucun matériel ici).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingTest {

    private val agree = EcKeys.generate()
    private val sign = EcKeys.generate()
    private val payload = PairingPayload(
        userId = "11111111-1111-4111-8111-111111111111",
        handle = "alice",
        agreeKey = EcKeys.encodePublic(agree.public),
        signKey = EcKeys.encodePublic(sign.public),
    )

    @Test
    fun `le QR se genere reellement en pixels lisibles`() {
        val link = payload.toLink()
        val bitmap = PairingQr.encode(link, 640)
        assertNotNull("Le QR doit être généré", bitmap)
        assertEquals(640, bitmap!!.width)
        assertEquals(640, bitmap.height)

        var black = 0
        var white = 0
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == android.graphics.Color.BLACK) black++ else if (pixel == android.graphics.Color.WHITE) white++
            }
        }
        assertTrue("le QR doit contenir des modules noirs", black > 500)
        assertTrue("le QR doit contenir une zone blanche", white > 500)
    }

    @Test
    fun `un lien invalide ne produit pas de QR`() {
        assertNull(PairingQr.encode("https://example.com/pas-un-lien"))
        assertNull(PairingQr.encode(""))
        assertTrue(PairingQr.isPairingLink(payload.toLink()))
    }

    @Test
    fun `l'etiquette NFC fait l'aller-retour sans perdre les cles`() {
        val link = payload.toLink()
        val message = PairingNfc.messageFor(link)
        assertNotNull("Le message NDEF doit être créé", message)
        val record = message!!.records.single()
        val uri = record.toUri()
        assertNotNull(uri)
        assertEquals(link, uri.toString())

        val parsed = PairingLink.parse(uri.toString())
        assertEquals(payload.userId, parsed!!.userId)
        assertEquals(payload.agreeKey, parsed.agreeKey)
        assertEquals(payload.signKey, parsed.signKey)
    }

    @Test
    fun `un lien non conforme n'est pas ecrit sur une etiquette`() {
        assertNull(PairingNfc.messageFor("tomate://pair?v=1&u=nope"))
    }
}
