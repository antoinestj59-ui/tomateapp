package tomate.core.crypto

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869), écrit à la main parce que l'API `HKDF` d'Android n'existe qu'à partir
 * de l'API 33 alors que TOMATE démarre à l'API 26. Le résultat est vérifié octet pour octet
 * contre WebCrypto (`deriveBits` avec `HKDF`) par `E2eeInteropTest`.
 */
object Hkdf {

    fun sha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "Longueur HKDF hors bornes." }
        val mac = Mac.getInstance("HmacSHA256")
        val effectiveSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val toCopy = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, toCopy)
            offset += toCopy
            counter += 1
        }
        return output
    }

    /** Aide de test : concatène proprement plusieurs tableaux. */
    fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val buffer = ByteBuffer.allocate(total)
        parts.forEach { buffer.put(it) }
        return buffer.array()
    }
}
