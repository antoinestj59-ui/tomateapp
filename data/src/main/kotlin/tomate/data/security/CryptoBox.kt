package tomate.data.security

/**
 * Tiny abstraction over local secret protection so the data layer is unit-testable without
 * an Android Keystore. Production uses [KeystoreBox]; tests use [InMemoryCryptoBox].
 */
interface CryptoBox {
    fun encrypt(plaintext: String): String
    fun decryptOrNull(payload: String): String?
}

class KeystoreCryptoBox(private val box: KeystoreBox = KeystoreBox()) : CryptoBox {
    override fun encrypt(plaintext: String): String = box.encrypt(plaintext)

    override fun decryptOrNull(payload: String): String? = try {
        box.decryptStringOrNull(payload)
    } catch (e: KeystoreUnavailable) {
        null
    }
}

/** Test double: reversible, obviously not secure, only used in JVM unit tests. */
class InMemoryCryptoBox : CryptoBox {
    override fun encrypt(plaintext: String): String = plaintext.reversed()

    override fun decryptOrNull(payload: String): String? = payload.reversed()
}
