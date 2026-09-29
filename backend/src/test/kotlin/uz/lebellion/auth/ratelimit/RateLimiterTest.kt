package uz.lebellion.auth.ratelimit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class RateLimiterTest {

    private class MutableClock(var instant: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun getZone() = zone
        override fun withZone(z: ZoneId) = MutableClock(instant, z)
        override fun instant() = instant
    }

    private val rule = RateLimiter.Rule(limit = 3, window = Duration.ofMinutes(1))

    @Test
    fun `allows up to the limit then denies within the window`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val limiter = RateLimiter(clock)

        assertTrue(limiter.tryAcquire("login:1.2.3.4", rule))
        assertTrue(limiter.tryAcquire("login:1.2.3.4", rule))
        assertTrue(limiter.tryAcquire("login:1.2.3.4", rule))
        assertFalse(limiter.tryAcquire("login:1.2.3.4", rule))
    }

    @Test
    fun `resets after the window elapses`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val limiter = RateLimiter(clock)

        repeat(3) { assertTrue(limiter.tryAcquire("k", rule)) }
        assertFalse(limiter.tryAcquire("k", rule))

        clock.instant = clock.instant.plusSeconds(61)
        assertTrue(limiter.tryAcquire("k", rule))
    }

    @Test
    fun `keys are independent`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val limiter = RateLimiter(clock)

        repeat(3) { assertTrue(limiter.tryAcquire("a", rule)) }
        assertFalse(limiter.tryAcquire("a", rule))
        assertTrue(limiter.tryAcquire("b", rule))
    }
}
