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

    @Test
    fun `account key throttles login across changing IPs`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val limiter = RateLimiter(clock)
        val ipRule = RateLimiter.Rule(limit = 10, window = Duration.ofMinutes(1))
        val userRule = RateLimiter.Rule(limit = 3, window = Duration.ofMinutes(15))

        // Same account, a fresh IP every time — the account bucket still fills up.
        repeat(3) { i ->
            assertTrue(
                limiter.tryAcquireAll(
                    listOf(
                        RateLimiter.Check("login-ip:$i.$i.$i.$i", ipRule),
                        RateLimiter.Check("login-user:bob@example.com", userRule),
                    ),
                ),
            )
        }
        // 4th attempt from a brand-new IP is denied by the account bucket.
        assertFalse(
            limiter.tryAcquireAll(
                listOf(
                    RateLimiter.Check("login-ip:9.9.9.9", ipRule),
                    RateLimiter.Check("login-user:bob@example.com", userRule),
                ),
            ),
        )
    }

    @Test
    fun `ip key throttles across different accounts`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val limiter = RateLimiter(clock)
        val ipRule = RateLimiter.Rule(limit = 3, window = Duration.ofMinutes(1))
        val userRule = RateLimiter.Rule(limit = 100, window = Duration.ofMinutes(15))

        // Same IP hammering many distinct accounts — the IP bucket still fills up.
        repeat(3) { i ->
            assertTrue(
                limiter.tryAcquireAll(
                    listOf(
                        RateLimiter.Check("login-ip:1.2.3.4", ipRule),
                        RateLimiter.Check("login-user:victim$i@example.com", userRule),
                    ),
                ),
            )
        }
        assertFalse(
            limiter.tryAcquireAll(
                listOf(
                    RateLimiter.Check("login-ip:1.2.3.4", ipRule),
                    RateLimiter.Check("login-user:another@example.com", userRule),
                ),
            ),
        )
    }
}
