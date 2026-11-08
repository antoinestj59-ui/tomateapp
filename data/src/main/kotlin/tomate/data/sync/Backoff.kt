package tomate.data.sync

import kotlin.math.min
import kotlin.random.Random

/**
 * Retry policy for outbox operations: exponential backoff, full jitter, hard cap.
 * Deterministic when a seeded [Random] is injected, which is what the unit tests use.
 */
class Backoff(
    private val baseDelayMs: Long = 2_000L,
    private val maxDelayMs: Long = 15L * 60L * 1_000L,
    private val maxAttempts: Int = 8,
    private val random: Random = Random.Default,
) {
    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0) return baseDelayMs
        val exponential = baseDelayMs * (1L shl min(attempt, 20).coerceAtMost(20))
        val capped = min(exponential, maxDelayMs)
        // Full jitter: spreads retries so a whole fleet does not hammer the backend at once.
        return (capped / 2) + random.nextLong(capped / 2 + 1)
    }

    fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts
}
