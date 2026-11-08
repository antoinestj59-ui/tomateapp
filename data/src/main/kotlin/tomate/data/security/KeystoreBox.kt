package tomate.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound encryption for locally stored secrets (recovery code, session material backup).
 *
 * This is NOT the end-to-end messaging protocol: it is the platform keystore protecting
 * at-rest data on this device. It only uses standard primitives (AES-256/GCM via AndroidKeyStore),
 * no custom cryptography. If the key is invalidated (factory reset, lock screen change) the
 * ciphertext becomes unreadable and TOMATE reports the secret as unavailable rather than
 * silently losing it.
 */
class KeystoreBox(
    private val alias: String = DEFAULT_ALIAS,
) {
    private companion object {
        const val DEFAULT_ALIAS = "tomate_local_secret_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /** Returns a Base64 string `iv||ciphertext`. */
    fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected GCM IV size: ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext)
        val combined = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(combined, 0)
        ciphertext.copyInto(combined, iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun encrypt(plaintext: String): String = encrypt(plaintext.toByteArray(Charsets.UTF_8))

    /** Throws [KeystoreUnavailable] or returns null if the payload cannot be decrypted. */
    fun decryptOrNull(payload: String): ByteArray? = try {
        val combined = Base64.decode(payload, Base64.NO_WRAP)
        if (combined.size <= IV_BYTES) {
            null
        } else {
            val iv = combined.copyOfRange(0, IV_BYTES)
            val ciphertext = combined.copyOfRange(IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        }
    } catch (t: Throwable) {
        throw KeystoreUnavailable(t)
    }

    fun decryptStringOrNull(payload: String): String? =
        decryptOrNull(payload)?.toString(Charsets.UTF_8)

    fun deleteKey() {
        runCatching { keyStore().deleteEntry(alias) }
    }
}

class KeystoreUnavailable(cause: Throwable) : Exception("Clé locale indisponible", cause)
