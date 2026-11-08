package tomate.data.security

import java.security.SecureRandom

/**
 * TOMATE recovery code.
 *
 * Design decision (see docs/04_SECURITE.md §3): an anonymous Supabase account cannot be
 * recovered from a handle alone, and TOMATE does not ship a privileged server function yet.
 * TOMATE therefore generates a high-entropy recovery code locally, stores it protected by the
 * Android Keystore and lets the user copy it. Until the server-side recovery function is
 * deployed, the app must present recovery as **not available**, never as working.
 *
 * The code is 26 base32 characters (130 bits of entropy from [SecureRandom]) grouped for
 * readability. It is not a password: it is a bearer secret, so it is shown once behind an
 * explicit confirmation and never logged.
 */
object RecoverySecret {

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Crockford-like, no 0/O/1/I
    private const val CODE_LENGTH = 26
    private const val GROUP_SIZE = 5

    fun generate(random: SecureRandom = SecureRandom()): String {
        val builder = StringBuilder(CODE_LENGTH + CODE_LENGTH / GROUP_SIZE)
        repeat(CODE_LENGTH) { index ->
            if (index > 0 && index % GROUP_SIZE == 0) builder.append('-')
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return builder.toString()
    }

    /** Normalises user input so pasted codes with spaces/dashes still match. */
    fun normalise(code: String): String =
        code.uppercase().filter { it.isLetterOrDigit() }

    fun isValidFormat(code: String): Boolean {
        val normalised = normalise(code)
        return normalised.length == CODE_LENGTH && normalised.all { it in ALPHABET }
    }
}
