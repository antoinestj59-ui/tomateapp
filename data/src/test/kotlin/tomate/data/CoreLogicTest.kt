package tomate.data

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tomate.data.repo.ProfileRepository
import tomate.data.security.RecoverySecret
import tomate.data.sync.Backoff

class BackoffTest {

    private val backoff = Backoff(
        baseDelayMs = 1_000,
        maxDelayMs = 60_000,
        maxAttempts = 5,
        random = Random(42),
    )

    @Test
    fun `delay stays within the jitter window`() {
        for (attempt in 0..10) {
            val delay = backoff.delayForAttempt(attempt)
            assertTrue("delay=$delay", delay >= 0)
            assertTrue("delay=$delay", delay <= 60_000)
        }
    }

    @Test
    fun `delay is capped`() {
        val delay = backoff.delayForAttempt(30)
        assertTrue(delay <= 60_000)
    }

    @Test
    fun `retry stops after max attempts`() {
        assertTrue(backoff.shouldRetry(1))
        assertTrue(backoff.shouldRetry(4))
        assertFalse(backoff.shouldRetry(5))
        assertFalse(backoff.shouldRetry(6))
    }

    @Test
    fun `jitter spreads identical attempts`() {
        val values = (1..20).map { backoff.delayForAttempt(3) }.toSet()
        assertTrue("expected jitter diversity, got $values", values.size > 1)
    }
}

class RecoverySecretTest {

    @Test
    fun `generated code has the expected format`() {
        val code = RecoverySecret.generate()
        assertTrue(RecoverySecret.isValidFormat(code))
        assertEquals(26, RecoverySecret.normalise(code).length)
        assertTrue(code.contains('-'))
    }

    @Test
    fun `generated codes are distinct`() {
        val codes = (1..50).map { RecoverySecret.generate() }.toSet()
        assertEquals(50, codes.size)
    }

    @Test
    fun `normalisation accepts pasted codes with separators`() {
        val code = RecoverySecret.generate()
        val messy = code.lowercase().replace("-", " ")
        assertEquals(RecoverySecret.normalise(code), RecoverySecret.normalise(messy))
        assertTrue(RecoverySecret.isValidFormat(messy))
    }

    @Test
    fun `invalid codes are rejected`() {
        assertFalse(RecoverySecret.isValidFormat(""))
        assertFalse(RecoverySecret.isValidFormat("ABC"))
        assertFalse(RecoverySecret.isValidFormat("0O1I" + "A".repeat(22)))
    }
}

class HandleValidationTest {

    @Test
    fun `valid handles are accepted`() {
        assertTrue(ProfileRepository.isValidHandle("tomateur"))
        assertTrue(ProfileRepository.isValidHandle("jean_dupont"))
        assertTrue(ProfileRepository.isValidHandle("abc123"))
    }

    @Test
    fun `invalid handles are rejected`() {
        assertFalse(ProfileRepository.isValidHandle("ab"))
        assertFalse(ProfileRepository.isValidHandle("Jean"))
        assertFalse(ProfileRepository.isValidHandle("jean dupont"))
        assertFalse(ProfileRepository.isValidHandle("jean-d"))
        assertFalse(ProfileRepository.isValidHandle("a".repeat(21)))
        assertFalse(ProfileRepository.isValidHandle("admin"))
        assertFalse(ProfileRepository.isValidHandle("tomate"))
    }

    @Test
    fun `normalisation strips decorations and lowercases`() {
        assertEquals("jean", ProfileRepository.normaliseHandle("  @Jean  "))
    }
}
