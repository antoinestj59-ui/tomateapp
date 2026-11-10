package tomate.app.pairing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import tomate.core.crypto.PairingLink

/**
 * QR d'appairage : le lien `tomate://pair` qui transporte l'identité **et** les clés publiques.
 *
 * Le QR est rendu par ZXing (dépendance pure Java, sans service Google), en correction d'erreur
 * moyenne : un lien d'appairage fait environ 250 caractères, ce qui reste confortable à scanner
 * même avec un objectif sale ou un écran peu lumineux.
 */
object PairingQr {

    /** Le lien fait ~250 caractères : sans correction d'erreur suffisante, un QR devient illisible. */
    const val RECOMMENDED_SIZE_PX = 640

    fun encode(text: String, sizePx: Int = RECOMMENDED_SIZE_PX): Bitmap? {
        // Un QR d'appairage sans clés n'apporte rien : on refuse d'en produire un.
        if (!isPairingLink(text)) return null
        val matrix = runCatching {
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 1,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        }.getOrNull() ?: return null

        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val row = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }
    }

    fun image(text: String, sizePx: Int = RECOMMENDED_SIZE_PX): ImageBitmap? =
        encode(text, sizePx)?.asImageBitmap()

    /** Vrai si la chaîne est un lien d'appairage valide (clés comprises). */
    fun isPairingLink(text: String): Boolean = PairingLink.parse(text) != null
}
