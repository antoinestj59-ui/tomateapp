package tomate.core.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Clés de courbe P-256 (secp256r1), le seul jeu d'algorithmes disponible **à la fois** dans le
 * Keystore Android, dans la JVM de test et dans WebCrypto (navigateur). C'est ce qui rend le
 * chiffrement de bout en bout de TOMATE interopérable entre l'app et la PWA sans dépendance
 * cryptographique externe.
 *
 * Représentations employées partout :
 *  - clé publique : point non compressé **65 octets** (`0x04 || X || Y`), identique à
 *    `exportKey('raw')` de WebCrypto ;
 *  - clé privée : encodage **PKCS#8**, accepté tel quel par `importKey('pkcs8')` ;
 *  - transport : base64url sans remplissage.
 */
object EcKeys {

    const val CURVE = "secp256r1"
    const val UNCOMPRESSED_POINT_BYTES = 65
    const val P256_COORDINATE_BYTES = 32

    @Volatile
    private var cachedParams: ECParameterSpec? = null

    fun generate(random: SecureRandom = SecureRandom()): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), random)
        return generator.generateKeyPair()
    }

    fun parameters(): ECParameterSpec {
        cachedParams?.let { return it }
        val parameters = java.security.AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec(CURVE))
        val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
        cachedParams = spec
        return spec
    }

    /** Point non compressé, exactement le format `raw` de WebCrypto. */
    fun encodePoint(key: PublicKey): ByteArray {
        val ec = key as? ECPublicKey
            ?: throw E2eeException("Clé publique EC attendue.")
        val x = fixedWidth(ec.w.affineX.toByteArray())
        val y = fixedWidth(ec.w.affineY.toByteArray())
        return ByteArray(UNCOMPRESSED_POINT_BYTES).also { out ->
            out[0] = 0x04
            x.copyInto(out, 1)
            y.copyInto(out, 1 + P256_COORDINATE_BYTES)
        }
    }

    fun decodePoint(raw: ByteArray): PublicKey {
        if (raw.size != UNCOMPRESSED_POINT_BYTES || raw[0] != 0x04.toByte()) {
            throw E2eeException("Point P-256 invalide.")
        }
        val x = java.math.BigInteger(1, raw.copyOfRange(1, 1 + P256_COORDINATE_BYTES))
        val y = java.math.BigInteger(
            1,
            raw.copyOfRange(1 + P256_COORDINATE_BYTES, UNCOMPRESSED_POINT_BYTES),
        )
        val spec = ECPublicKeySpec(ECPoint(x, y), parameters())
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    fun encodePrivate(key: PrivateKey): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(key.encoded)

    fun decodePrivate(encoded: String): PrivateKey? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(encoded)
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }.getOrNull()

    fun encodePublic(key: PublicKey): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(encodePoint(key))

    fun decodePublic(encoded: String): PublicKey? = runCatching {
        decodePoint(Base64.getUrlDecoder().decode(encoded))
    }.getOrNull()

    /** Secret partagé ECDH : la coordonnée X, soit exactement `deriveBits` de WebCrypto. */
    fun agree(privateKey: PrivateKey, peerPublicKey: PublicKey, random: SecureRandom? = null): ByteArray {
        val agreement = javax.crypto.KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    /** Empreinte courte et stable d'une clé publique, affichable à l'utilisateur. */
    fun fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encodePoint(key))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(8))
    }

    fun fingerprint(encodedPublic: String): String? =
        decodePublic(encodedPublic)?.let { fingerprint(it) }

    /** Taille réelle d'une clé privée P-256 en octets (utilisé par les tests d'invariants). */
    fun privateScalarBytes(key: PrivateKey): Int =
        (key as? ECPrivateKey)?.s?.toByteArray()?.size ?: 0

    private fun fixedWidth(value: ByteArray): ByteArray {
        val trimmed = if (value.size > P256_COORDINATE_BYTES && value[0] == 0.toByte()) {
            value.copyOfRange(value.size - P256_COORDINATE_BYTES, value.size)
        } else {
            value
        }
        if (trimmed.size > P256_COORDINATE_BYTES) throw E2eeException("Coordonnée P-256 trop grande.")
        return ByteArray(P256_COORDINATE_BYTES).also { trimmed.copyInto(it, P256_COORDINATE_BYTES - trimmed.size) }
    }
}
