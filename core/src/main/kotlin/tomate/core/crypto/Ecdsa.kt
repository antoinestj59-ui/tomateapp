package tomate.core.crypto

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * ECDSA P-256 / SHA-256, avec conversion entre les deux encodages que les plateformes imposent :
 *
 *  - Java signe et vérifie en **DER** (`SEQUENCE { INTEGER r, INTEGER s }`) ;
 *  - WebCrypto signe et vérifie en **raw** (`r || s`, 64 octets).
 *
 * TOMATE transporte toujours le format raw de 64 octets ; la conversion est faite ici, à la
 * frontière, pour que les deux clients produisent exactement la même signature vérifiable.
 */
object Ecdsa {

    const val RAW_SIGNATURE_BYTES = 64

    fun signRaw(privateKey: PrivateKey, data: ByteArray): ByteArray =
        derToRaw(signDer(privateKey, data))

    fun verifyRaw(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != RAW_SIGNATURE_BYTES) return false
        return runCatching { verifyDer(publicKey, data, rawToDer(signature)) }.getOrDefault(false)
    }

    fun signDer(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    fun verifyDer(publicKey: PublicKey, data: ByteArray, derSignature: ByteArray): Boolean {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(data)
        return verifier.verify(derSignature)
    }

    /**
     * DER -> raw. Strict : refuse tout ce qui n'est pas une séquence de deux entiers tenant sur
     * 32 octets, plutôt que de « réparer » silencieusement une signature douteuse.
     */
    fun derToRaw(der: ByteArray): ByteArray {
        val reader = DerReader(der)
        val sequence = reader.readSequence()
        val inner = DerReader(sequence)
        val r = trimToScalar(inner.readInteger())
        val s = trimToScalar(inner.readInteger())
        if (inner.hasRemaining()) throw E2eeException("Signature DER avec octets en trop.")
        return ByteArray(RAW_SIGNATURE_BYTES).also { out ->
            r.copyInto(out, EcKeys.P256_COORDINATE_BYTES - r.size)
            s.copyInto(out, 2 * EcKeys.P256_COORDINATE_BYTES - s.size)
        }
    }

    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == RAW_SIGNATURE_BYTES) { "Signature raw de 64 octets attendue." }
        val r = toPositiveInteger(raw.copyOfRange(0, EcKeys.P256_COORDINATE_BYTES))
        val s = toPositiveInteger(raw.copyOfRange(EcKeys.P256_COORDINATE_BYTES, RAW_SIGNATURE_BYTES))
        val content = r + s
        val header = derHeader(0x30, content.size)
        return header + content
    }

    private fun trimToScalar(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size - 1 && value[start] == 0.toByte()) start += 1
        val trimmed = value.copyOfRange(start, value.size)
        if (trimmed.size > EcKeys.P256_COORDINATE_BYTES) throw E2eeException("Entier DER trop grand.")
        return trimmed
    }

    private fun toPositiveInteger(value: ByteArray): ByteArray {
        val trimmed = trimToScalar(value)
        val needsPad = (trimmed[0].toInt() and 0x80) != 0
        val body = if (needsPad) byteArrayOf(0) + trimmed else trimmed
        return derHeader(0x02, body.size) + body
    }

    private fun derHeader(tag: Int, length: Int): ByteArray {
        if (length < 0x80) return byteArrayOf(tag.toByte(), length.toByte())
        val lengthBytes = buildList {
            var remaining = length
            while (remaining > 0) {
                add((remaining and 0xFF).toByte())
                remaining = remaining shr 8
            }
        }.reversed().toByteArray()
        return byteArrayOf(tag.toByte(), (0x80 or lengthBytes.size).toByte()) + lengthBytes
    }

    private class DerReader(private val data: ByteArray) {
        private var position = 0

        fun hasRemaining(): Boolean = position < data.size

        fun readSequence(): ByteArray {
            expect(0x30)
            return readBody()
        }

        fun readInteger(): ByteArray {
            expect(0x02)
            return readBody()
        }

        private fun expect(tag: Int) {
            if (position >= data.size || data[position].toInt() and 0xFF != tag) {
                throw E2eeException("Structure DER inattendue.")
            }
            position += 1
        }

        private fun readBody(): ByteArray {
            if (position >= data.size) throw E2eeException("DER tronqué.")
            val first = data[position++].toInt() and 0xFF
            val length = if (first and 0x80 == 0) {
                first
            } else {
                val count = first and 0x7F
                if (count == 0 || count > 3) throw E2eeException("Longueur DER invalide.")
                var value = 0
                repeat(count) {
                    if (position >= data.size) throw E2eeException("DER tronqué.")
                    value = (value shl 8) or (data[position++].toInt() and 0xFF)
                }
                value
            }
            if (length < 0 || position + length > data.size) throw E2eeException("DER tronqué.")
            val body = data.copyOfRange(position, position + length)
            position += length
            return body
        }
    }
}
