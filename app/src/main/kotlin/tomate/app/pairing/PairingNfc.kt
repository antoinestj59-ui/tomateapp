package tomate.app.pairing

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import tomate.core.crypto.PairingLink

/**
 * Appairage par étiquette NFC : exactement le même lien que le QR, écrit dans un enregistrement
 * NDEF « URI ». Approcher le téléphone de l'étiquette ouvre TOMATE sur `tomate://pair?…`.
 *
 * L'écriture sur l'étiquette elle-même demande un appareil NFC et une activité en premier plan :
 * `NdefRecord` est testé (aller-retour du lien), la puce ne l'est pas ici.
 */
object PairingNfc {

    const val SCHEME = "tomate"

    /** Enregistrement NDEF à écrire sur une étiquette, ou `null` si le lien est invalide. */
    fun recordFor(link: String): NdefRecord? {
        if (PairingLink.parse(link) == null) return null
        return runCatching { NdefRecord.createUri(Uri.parse(link)) }.getOrNull()
    }

    fun messageFor(link: String): NdefMessage? = recordFor(link)?.let { NdefMessage(arrayOf(it)) }

    /** Lit un lien d'appairage depuis l'intent qui a ouvert l'app (tag ou Beam). */
    fun readLink(intent: Intent?): String? {
        if (intent == null) return null
        val raw = intent.getParcelableArrayExtra(android.nfc.NfcAdapter.EXTRA_NDEF_MESSAGES)
            ?: return intent.dataString
        val messages = raw.filterIsInstance<NdefMessage>()
        for (message in messages) {
            for (record in message.records) {
                val uri = runCatching { record.toUri() }.getOrNull() ?: continue
                val link = uri.toString()
                if (PairingLink.parse(link) != null) return link
            }
        }
        return null
    }

    fun readPayload(intent: Intent?) = readLink(intent)?.let { PairingLink.parse(it) }
}
